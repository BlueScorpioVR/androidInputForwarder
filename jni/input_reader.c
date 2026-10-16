// Root-only, read-only evdev helper. No grabs, sockets, uinput, or persistence.
// stdin is a lifetime pipe: closing it stops this process even if the UI/service dies.
#include <linux/input.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <poll.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>

#define MAX_DEVICES 32
#define ID_SIZE 1700
#define BIT(a,n) (((a)[(n)/8] >> ((n)%8)) & 1)
typedef struct { int fd; char path[128], id[ID_SIZE]; } device;
static device devices[MAX_DEVICES];
static void hex(const char *s, char *out) {
    const char *h = "0123456789abcdef";
    while (*s) { unsigned char c = (unsigned char)*s++; *out++=h[c>>4]; *out++=h[c&15]; }
    *out=0;
}
static int identity(int fd, char *id, char *label, int *roles) {
    char name[256]={0}, phys[256]={0}, uniq[256]={0};
    char hn[512], hp[512], hu[512];
    struct input_id info={0};
    unsigned char keys[(KEY_MAX+8)/8]={0}, rel[(REL_MAX+8)/8]={0}, abs[(ABS_MAX+8)/8]={0};
    if (ioctl(fd,EVIOCGNAME(sizeof(name)-1),name)<0 || ioctl(fd,EVIOCGID,&info)<0) return -1;
    ioctl(fd,EVIOCGPHYS(sizeof(phys)-1),phys);
    ioctl(fd,EVIOCGUNIQ(sizeof(uniq)-1),uniq);
    ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(keys)),keys);
    ioctl(fd,EVIOCGBIT(EV_REL,sizeof(rel)),rel);
    ioctl(fd,EVIOCGBIT(EV_ABS,sizeof(abs)),abs);
    *roles = (BIT(keys,BTN_GAMEPAD)&&BIT(abs,ABS_X)&&BIT(abs,ABS_Y)?1:0)
        | (BIT(keys,KEY_A)&&BIT(keys,KEY_ENTER)?2:0)
        | (BIT(keys,BTN_LEFT)&&BIT(rel,REL_X)&&BIT(rel,REL_Y)?4:0);
    hex(name,hn); hex(phys,hp); hex(uniq,hu); strcpy(label,hn);
    snprintf(id,ID_SIZE,"%04x-%04x-%04x-%04x:%s:%s:%s",info.bustype,info.vendor,info.product,info.version,hn,hu,hp);
    return 0;
}
static void snapshot(device *d) {
    unsigned char abs[(ABS_MAX+8)/8]={0}, keys[(KEY_MAX+8)/8]={0};
    ioctl(d->fd,EVIOCGBIT(EV_ABS,sizeof(abs)),abs);
    for (int code=0;code<=ABS_MAX;code++) if (BIT(abs,code)) {
        struct input_absinfo a;
        if (ioctl(d->fd,EVIOCGABS(code),&a)==0) {
            printf("A\t%s\t%d\t%d\t%d\t%d\n",d->id,code,a.minimum,a.maximum,a.flat);
            printf("E\t%s\t%d\t%d\t%d\n",d->id,EV_ABS,code,a.value);
        }
    }
    ioctl(d->fd,EVIOCGKEY(sizeof(keys)),keys);
    for (int code=0;code<=KEY_MAX;code++) if (BIT(keys,code)) printf("E\t%s\t1\t%d\t1\n",d->id,code);
    printf("E\t%s\t0\t0\t0\n",d->id);
}
static void remove_device(device *d) {
    printf("R\t%s\n",d->id); close(d->fd); d->fd=-1;
}
static void scan(int argc,char **argv,int list) {
    DIR *dir=opendir("/dev/input");
    if (!dir) { fprintf(stderr,"Cannot open /dev/input: %s\n",strerror(errno)); exit(2); }
    struct dirent *entry;
    while ((entry=readdir(dir))) {
        if (strncmp(entry->d_name,"event",5)!=0) continue;
        char path[128];
        if (snprintf(path,sizeof(path),"/dev/input/%s",entry->d_name)>=(int)sizeof(path)) continue;
        int seen=0;
        for (int i=0;i<MAX_DEVICES;i++) if (devices[i].fd>=0 && !strcmp(devices[i].path,path)) seen=1;
        if (seen) continue;
        int fd=open(path,O_RDONLY|O_NONBLOCK|O_CLOEXEC);
        if (fd<0) continue;
        char id[ID_SIZE], label[512]; int roles=0;
        if (identity(fd,id,label,&roles)<0 || !roles) { close(fd); continue; }
        if (list) { printf("D\t%s\t%s\t%s\t%d\n",path,id,label,roles); close(fd); continue; }
        int selected=0;
        for (int i=2;i<argc;i++) if (!strcmp(argv[i],id)) selected=1;
        if (!selected) { close(fd); continue; }
        int slot;
        for (slot=0;slot<MAX_DEVICES && devices[slot].fd>=0;slot++);
        if (slot==MAX_DEVICES) { close(fd); continue; }
        device *d=&devices[slot]; d->fd=fd; strcpy(d->id,id); strcpy(d->path,path);
        printf("D\t%s\t%s\t%s\t%d\n",path,id,label,roles); snapshot(d);
    }
    closedir(dir);
}
int main(int argc,char **argv) {
    for(int i=0;i<MAX_DEVICES;i++) devices[i].fd=-1;
    setvbuf(stdout,NULL,_IOLBF,0);
    prctl(PR_SET_PDEATHSIG,SIGTERM);
    if (argc==2 && !strcmp(argv[1],"--list")) { scan(argc,argv,1); return 0; }
    if (argc<3 || strcmp(argv[1],"--read")) { fprintf(stderr,"Usage: --list | --read identity...\n"); return 2; }
    if (getppid()==1) return 2;
    time_t last_scan=0;
    int dropping[MAX_DEVICES]={0};
    for (;;) {
        if (time(NULL)!=last_scan) { scan(argc,argv,0); last_scan=time(NULL); }
        struct pollfd p[MAX_DEVICES+1];
        p[0]=(struct pollfd){STDIN_FILENO,POLLIN,0};
        for(int i=0;i<MAX_DEVICES;i++) p[i+1]=(struct pollfd){devices[i].fd,POLLIN,0};
        if (poll(p,MAX_DEVICES+1,250)<0) { if(errno==EINTR) continue; break; }
        if (p[0].revents&(POLLIN|POLLHUP|POLLERR)) break;
        for(int i=0;i<MAX_DEVICES;i++) {
            device *d=&devices[i]; if(d->fd<0) continue;
            if(p[i+1].revents&(POLLHUP|POLLERR|POLLNVAL)) { remove_device(d); dropping[i]=0; continue; }
            if(!(p[i+1].revents&POLLIN)) continue;
            struct input_event e[64]; ssize_t n=read(d->fd,e,sizeof(e));
            if(n<=0) { if(n==0 || errno!=EAGAIN) remove_device(d); continue; }
            for(size_t j=0;j<(size_t)n/sizeof(e[0]);j++) {
                if(e[j].type==EV_SYN && e[j].code==SYN_DROPPED) { dropping[i]=1; continue; }
                if(dropping[i]) {
                    if(e[j].type==EV_SYN && e[j].code==SYN_REPORT) {
                        printf("R\t%s\n",d->id);
                        char id[ID_SIZE], label[512]; int roles;
                        if(identity(d->fd,id,label,&roles)==0) printf("D\t%s\t%s\t%s\t%d\n",d->path,d->id,label,roles);
                        snapshot(d); dropping[i]=0;
                    }
                    continue;
                }
                if(e[j].type<=EV_ABS) printf("E\t%s\t%d\t%d\t%d\n",d->id,e[j].type,e[j].code,e[j].value);
            }
        }
    }
    for(int i=0;i<MAX_DEVICES;i++) if(devices[i].fd>=0) remove_device(&devices[i]);
    return 0;
}

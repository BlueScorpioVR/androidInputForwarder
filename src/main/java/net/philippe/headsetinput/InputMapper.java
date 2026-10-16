package net.philippe.headsetinput;

import java.util.*;

/** Linux evdev -> GameStream input. Pure Java so traces can be tested off-device. */
public final class InputMapper {
    public interface Sink {
        void key(int vk, boolean down, int modifiers);
        void mouseButton(int button, boolean down);
        void mouseMove(int x, int y);
        void scroll(int vertical, int horizontal);
        void gamepad(int buttons, int lt, int rt, int lx, int ly, int rx, int ry, boolean present);
    }
    private static final class Device {
        final int roles;
        final Set<Integer> keys = new HashSet<>();
        final Map<Integer,int[]> ranges = new HashMap<>();
        final Map<Integer,Integer> axes = new HashMap<>();
        int dx,dy,wheel,hwheel,hiWheel,hiHWheel;
        boolean hasHiWheel,hasHiHWheel;
        Device(int roles) { this.roles=roles; }
    }
    private final Sink sink;
    private final Map<String,Device> devices = new LinkedHashMap<>();
    public InputMapper(Sink sink) { this.sink=sink; }
    public int deviceCount() { return devices.size(); }
    public void add(String id,int roles) {
        if(devices.containsKey(id)) remove(id);
        if((roles&1)!=0 && devices.values().stream().anyMatch(d -> (d.roles&1)!=0))
            throw new IllegalArgumentException("Select only one Xbox controller");
        devices.put(id,new Device(roles));
    }
    public void range(String id,int code,int min,int max,int flat) {
        Device d=devices.get(id); if(d!=null) d.ranges.put(code,new int[]{min,max,flat});
    }
    public void event(String id,int type,int code,int value) {
        Device d=devices.get(id); if(d==null) return;
        if(type==1) {
            boolean was=d.keys.contains(code), down=value!=0;
            if(down) d.keys.add(code); else d.keys.remove(code);
            // Host performs key repeat. Do not duplicate Linux autorepeat events.
            if(was!=down) {
                if((d.roles&2)!=0 && vk(code)!=0 && !heldElsewhere(d,code)) sink.key(vk(code),down,modifiers());
                if((d.roles&4)!=0 && mouseButton(code)!=0 && !heldElsewhere(d,code)) sink.mouseButton(mouseButton(code),down);
            }
        } else if(type==3 && (d.roles&1)!=0) d.axes.put(code,value);
        else if(type==2 && (d.roles&4)!=0) {
            switch(code) {
                case 0: d.dx+=value; break; case 1: d.dy+=value; break;
                case 8: d.wheel+=value; break; case 6: d.hwheel+=value; break;
                case 11: d.hiWheel+=value; d.hasHiWheel=true; break;
                case 12: d.hiHWheel+=value; d.hasHiHWheel=true; break;
            }
        } else if(type==0 && code==0) flush(d);
    }
    private boolean heldElsewhere(Device d,int code) { return devices.values().stream().anyMatch(o -> o!=d && o.keys.contains(code)); }
    private boolean held(int... codes) {
        for(Device d:devices.values()) for(int code:codes) if(d.keys.contains(code)) return true;
        return false;
    }
    private int modifiers() { return (held(42,54)?1:0)|(held(29,97)?2:0)|(held(56,100)?4:0)|(held(125,126)?8:0); }
    private static int mouseButton(int code) {
        switch(code) { case 272:return 1; case 273:return 3; case 274:return 2; case 275:return 4; case 276:return 5; default:return 0; }
    }
    private static int button(int code) {
        switch(code) {
            case 304:return 0x1000; case 305:return 0x2000; case 307:return 0x4000; case 308:return 0x8000;
            case 310:return 0x100; case 311:return 0x200; case 314:return 0x20; case 315:return 0x10;
            case 316:return 0x400; case 317:return 0x40; case 318:return 0x80;
            case 544:return 1; case 545:return 2; case 546:return 4; case 547:return 8;
            case 167:case  recordShare:return 0x200000;
            default:return 0;
        }
    }
    private static final int recordShare=0x2c0;
    static int normalize(int value,int min,int max,int flat,boolean trigger,boolean invert) {
        if(max<=min) return 0;
        if(trigger) return Math.max(0,Math.min(255,(int)Math.round((value-(double)min)*255/(max-(double)min))));
        double center=(min+(double)max)/2;
        if(Math.abs(value-center)<=Math.max(flat,(max-(double)min)*0.035)) return 0;
        double n=(value-center)/((max-(double)min)/2);
        if(invert) n=-n;
        return (int)Math.round(Math.max(-1,Math.min(1,n))*32767);
    }
    private int axis(Device d,int code,boolean trigger,boolean invert) {
        int[] r=d.ranges.get(code); Integer v=d.axes.get(code);
        return r==null || v==null ? 0 : normalize(v,r[0],r[1],r[2],trigger,invert);
    }
    private void flush(Device d) {
        if((d.roles&4)!=0) {
            while(d.dx!=0 || d.dy!=0) {
                int x=Math.max(-32767,Math.min(32767,d.dx)), y=Math.max(-32767,Math.min(32767,d.dy));
                sink.mouseMove(x,y); d.dx-=x; d.dy-=y;
            }
            int v=d.hasHiWheel?d.hiWheel:d.wheel*120, h=d.hasHiHWheel?d.hiHWheel:d.hwheel*120;
            if(v!=0||h!=0) sink.scroll(v,h);
            d.wheel=d.hwheel=d.hiWheel=d.hiHWheel=0;
        }
        if((d.roles&1)!=0) {
            int flags=0; for(int k:d.keys) flags|=button(k);
            int hatX=d.axes.getOrDefault(16,0), hatY=d.axes.getOrDefault(17,0);
            flags|=hatX<0?4:hatX>0?8:0; flags|=hatY<0?1:hatY>0?2:0;
            // Linux Xbox HID: Z/RZ triggers and RX/RY right stick. Older Bluetooth
            // firmware uses Z/RZ right stick with BRAKE/GAS triggers.
            boolean brakeGas=d.ranges.containsKey(9)&&d.ranges.containsKey(10);
            int lt=axis(d,brakeGas?10:2,true,false),rt=axis(d,brakeGas?9:5,true,false);
            if(d.keys.contains(312))lt=255; if(d.keys.contains(313))rt=255;
            sink.gamepad(flags,lt,rt,axis(d,0,false,false),axis(d,1,false,true),
                axis(d,brakeGas?2:3,false,false),axis(d,brakeGas?5:4,false,true),true);
        }
    }
    public void remove(String id) {
        Device d=devices.remove(id); if(d==null)return;
        for(int k:new HashSet<>(d.keys)) {
            if(!held(k)) {
                if((d.roles&2)!=0 && vk(k)!=0)sink.key(vk(k),false,modifiers());
                if((d.roles&4)!=0 && mouseButton(k)!=0)sink.mouseButton(mouseButton(k),false);
            }
        }
        if((d.roles&1)!=0)sink.gamepad(0,0,0,0,0,0,0,false);
    }
    public void releaseAll() { for(String id:new ArrayList<>(devices.keySet())) remove(id); }
    public static int vk(int code) {
        if(code>=2&&code<=10)return '1'+code-2;
        if(code>=59&&code<=68)return 0x70+code-59;
        if(code>=16&&code<=25)return "QWERTYUIOP".charAt(code-16);
        if(code>=30&&code<=38)return "ASDFGHJKL".charAt(code-30);
        if(code>=44&&code<=50)return "ZXCVBNM".charAt(code-44);
        switch(code) {
            case 1:return 0x1b;case 11:return '0';case 12:return 0xbd;case 13:return 0xbb;
            case 14:return 8;case 15:return 9;case 26:return 0xdb;case 27:return 0xdd;
            case 28:case 96:return 13;case 29:return 0xa2;case 97:return 0xa3;
            case 39:return 0xba;case 40:return 0xde;case 41:return 0xc0;case 42:return 0xa0;
            case 43:return 0xdc;case 51:return 0xbc;case 52:return 0xbe;case 53:return 0xbf;
            case 54:return 0xa1;case 55:return 0x6a;case 56:return 0xa4;case 100:return 0xa5;
            case 57:return 32;case 58:return 0x14;case 69:return 0x90;case 70:return 0x91;
            case 71:return 0x67;case 72:return 0x68;case 73:return 0x69;case 74:return 0x6d;
            case 75:return 0x64;case 76:return 0x65;case 77:return 0x66;case 78:return 0x6b;
            case 79:return 0x61;case 80:return 0x62;case 81:return 0x63;case 82:return 0x60;
            case 83:return 0x6e;case 86:return 0xe2;case 87:return 0x7a;case 88:return 0x7b;
            case 98:return 0x6f;case 99:return 0x2c;case 102:return 0x24;case 103:return 0x26;
            case 104:return 0x21;case 105:return 0x25;case 106:return 0x27;case 107:return 0x23;
            case 108:return 0x28;case 109:return 0x22;case 110:return 0x2d;case 111:return 0x2e;
            case 113:return 0xad;case 114:return 0xae;case 115:return 0xaf;case 119:return 0x13;
            case 125:return 0x5b;case 126:return 0x5c;case 127:return 0x5d;
            case 163:return 0xb0;case 164:return 0xb3;case 165:return 0xb1;case 166:return 0xb2;
            default:return 0;
        }
    }
}

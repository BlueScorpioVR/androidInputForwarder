package net.philippe.headsetinput;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class InputMapperTest {
    static class Output implements InputMapper.Sink {
        final List<String> messages=new ArrayList<>(); int[] pad;
        public void key(int vk,boolean down,int mods){messages.add("key:"+vk+":"+down+":"+mods);}
        public void mouseButton(int b,boolean down){messages.add("button:"+b+":"+down);}
        public void mouseMove(int x,int y){messages.add("move:"+x+":"+y);}
        public void scroll(int v,int h){messages.add("scroll:"+v+":"+h);}
        public void gamepad(int b,int lt,int rt,int lx,int ly,int rx,int ry,boolean present){pad=new int[]{b,lt,rt,lx,ly,rx,ry,present?1:0};}
    }
    @Test public void xboxUnsignedSticksAndTriggers() {
        Output o=new Output();InputMapper m=new InputMapper(o);m.add("xbox",1);
        for(int a:new int[]{0,1,3,4})m.range("xbox",a,0,65535,0);
        m.range("xbox",2,0,1023,0);m.range("xbox",5,0,1023,0);
        m.event("xbox",3,0,65535);m.event("xbox",3,1,0);m.event("xbox",3,2,1023);
        m.event("xbox",3,3,32768);m.event("xbox",3,4,32768);
        m.event("xbox",1,304,1);m.event("xbox",3,17,-1);m.event("xbox",0,0,0);
        assertArrayEquals(new int[]{0x1001,255,0,32767,32767,0,0,1},o.pad);
        m.remove("xbox");assertArrayEquals(new int[8],o.pad);
    }
    @Test public void olderBluetoothAxisLayout() {
        Output o=new Output();InputMapper m=new InputMapper(o);m.add("pad",1);
        for(int a:new int[]{0,1,2,5})m.range("pad",a,-32768,32767,0);
        m.range("pad",9,0,1023,0);m.range("pad",10,0,1023,0);
        m.event("pad",3,2,32767);m.event("pad",3,10,1023);m.event("pad",0,0,0);
        assertEquals(255,o.pad[1]);assertEquals(32767,o.pad[5]);assertEquals(0,o.pad[2]);
    }
    @Test public void repeatsIgnoredAndDisconnectReleasesKeys() {
        Output o=new Output();InputMapper m=new InputMapper(o);m.add("keyboard",2);
        m.event("keyboard",1,29,1);m.event("keyboard",1,30,1);m.event("keyboard",1,30,2);
        assertEquals(Arrays.asList("key:162:true:2","key:65:true:2"),o.messages);
        m.remove("keyboard");assertTrue(o.messages.contains("key:65:false:0"));assertTrue(o.messages.contains("key:162:false:0"));
    }
    @Test public void sharedKeysRemainHeldUntilLastKeyboardReleases() {
        Output o=new Output();InputMapper m=new InputMapper(o);m.add("a",2);m.add("b",2);
        m.event("a",1,30,1);m.event("b",1,30,1);m.remove("a");
        assertEquals(1,o.messages.size());m.remove("b");assertEquals("key:65:false:0",o.messages.get(1));
    }
    @Test public void mouseHighResolutionWheelIsNotDoubleCounted() {
        Output o=new Output();InputMapper m=new InputMapper(o);m.add("mouse",4);
        m.event("mouse",2,0,-15);m.event("mouse",2,1,10);m.event("mouse",2,8,1);m.event("mouse",2,11,120);
        m.event("mouse",1,273,1);m.event("mouse",0,0,0);m.releaseAll();
        assertEquals(Arrays.asList("button:3:true","move:-15:10","scroll:120:0","button:3:false"),o.messages);
    }
    @Test public void unselectedEventsNeverReachSink() {
        Output o=new Output();InputMapper m=new InputMapper(o);
        m.event("unknown",1,30,1);m.event("unknown",0,0,0);assertTrue(o.messages.isEmpty());assertNull(o.pad);
    }
    @Test public void droppedStateCanBeReleasedThenResynchronised() {
        Output o=new Output();InputMapper m=new InputMapper(o);m.add("pad",1);m.event("pad",1,304,1);m.event("pad",0,0,0);
        m.remove("pad");assertEquals(0,o.pad[7]);m.add("pad",1);m.event("pad",1,305,1);m.event("pad",0,0,0);
        assertEquals(0x2000,o.pad[0]);
    }
    @Test public void outOfRangeAxisClampedAndDegenerateRangeNeutral() {
        assertEquals(32767,InputMapper.normalize(999,0,100,0,false,false));
        assertEquals(0,InputMapper.normalize(50,50,50,0,true,false));
        assertEquals(0,InputMapper.normalize(32768,0,65535,0,false,false));
    }
    @Test(expected=IllegalArgumentException.class) public void secondGamepadRejected() {
        InputMapper m=new InputMapper(new Output());m.add("a",1);m.add("b",1);
    }
}

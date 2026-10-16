package net.philippe.headsetinput;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
public class RemoteInputTest {
    @Test public void selectsVerifiedUuidRatherThanDisplayName() throws Exception {
        String xml="<root><App><AppTitle>Remote Input</AppTitle><ID>7</ID><UUID>other</UUID></App>"+
            "<App><AppTitle>Hidden prefix</AppTitle><ID>42</ID><UUID>8CB5C136-DA67-4F99-B4A1-F9CD35005CF4</UUID></App></root>";
        assertEquals(42,InputSession.findRemoteInput(xml));
    }
    @Test(expected=IOException.class) public void neverFallsBackToDesktopOrSameNamedApp() throws Exception {
        InputSession.findRemoteInput("<root><App><AppTitle>Remote Input</AppTitle><ID>7</ID></App></root>");
    }
}

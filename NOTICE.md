Android Input Forwarder is free software under the GNU General Public License,
version 3 or later. See `LICENSE.txt`.

It is derived from [Moonlight Android](https://github.com/moonlight-stream/moonlight-android),
commit `b48494cb96bff23d8886c4775cc4f39a1075495d`. The native protocol library
comes from that tree's `moonlight-common-c` submodule, commit
`874ac9548f1bd6f095ef2b435c42cdde460e7821`.

The Java classes under `src/main/java/com/limelight` are from that Moonlight
revision, with a small addition in `NvHTTP` so this app can launch Apollo's
Remote Input target without the desktop client's unbounded launch wait.
`src/main/java/net/philippe/headsetinput` and `jni/input_reader.c` are the
forwarder itself.

Moonlight Android is included as the `third_party/moonlight-android` submodule
so the native library can be rebuilt from the same revision.

package com.floatclip.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Debug-only, same-app test harness. Readiness secrets go to the invoking ADB session, not logcat. */
public final class LanProbeInstrumentation extends Instrumentation {
    private String address;
    private int port;
    private LanProbe probe;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        address = arguments == null ? "" : arguments.getString("address", "");
        port = arguments == null ? 46237 : Integer.parseInt(arguments.getString("port", "46237"));
        start();
    }
    @Override public void onStart() {
        probe = new LanProbe(new LanProbe.Events() {
            @Override public void ready(String ip, int p, String pin, String invitation) {
                Bundle ready = new Bundle();
                ready.putString("address", ip);
                ready.putString("port", Integer.toString(p));
                ready.putString("pin", pin);
                ready.putString("invitation", invitation);
                sendStatus(10, ready);
            }
            @Override public void event(String message) {
                Bundle event = new Bundle(); event.putString("message", message); sendStatus(20, event);
            }
            @Override public void ended(boolean success) {
                probe.close();
                Bundle result = new Bundle(); result.putString("result", success ? "PASS" : "FAIL");
                finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
            }
        });
        try { probe.start(address, port); }
        catch (Exception error) {
            probe.close();
            Bundle result = new Bundle(); result.putString("result", "FAIL: " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}

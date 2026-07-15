package demo;

import java.util.concurrent.TimeUnit;

class HeartbeatGauge {

    double secondsSinceLastHeartbeat(long lastHeartbeatMillis, long nowMillis) {
        if (lastHeartbeatMillis < 0L)
            // no heartbeat has been sent yet, so report the sentinel
            return -1d;
        else
            return TimeUnit.SECONDS.convert(nowMillis - lastHeartbeatMillis, TimeUnit.MILLISECONDS);
    }

    String describe(int status) {
        if (status < 0)
            // negative status codes are reserved for transport errors
            return "error";
        else {
            return "ok";
        }
    }
}

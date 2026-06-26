package com.example.fleet;

public interface RouteChannel {

    /**
     * Returns the channel identifier for the route.
     * @return the channel identifier
     */
    String getChannelID();

    class Beacon {
        int marker;
    }// inner beacon

}// RouteChannel

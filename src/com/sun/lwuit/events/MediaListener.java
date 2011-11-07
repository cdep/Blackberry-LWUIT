/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.sun.lwuit.events;

/**
 *
 * @author chenf
 */
public interface MediaListener {
    
    /**
     * Type value for media started
     */
    public static int STARTED = 0;
    
    /**
     * Type value for media stopped
     */
    public static int STOPPED = 1;
    
    /**
     * Type value for media closed
     */
    public static int CLOSED = 2;

    /**
     * Type value for media ended
     */
    public static int ENDED = 3;
    
    /**
     * Type value for media error
     */
    public static int ERROR = 4;
    
    
    public void playerUpdated(int state);
    
}

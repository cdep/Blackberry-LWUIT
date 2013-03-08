/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores
 * CA 94065 USA or visit www.oracle.com if you need additional information or
 * have any questions.
 */

package com.sun.lwuit.io;

import com.sun.lwuit.Display;
import com.sun.lwuit.events.ActionEvent;
import com.sun.lwuit.events.ActionListener;
import com.sun.lwuit.io.impl.IOImplementation;
import com.sun.lwuit.util.EventDispatcher;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Main entry point for managing the connection requests, this is essentially a
 * threaded queue that makes sure to route all connections via the network thread
 * while sending the callbacks through the LWUIT EDT.
 *
 * @author Shai Almog
 */
public class NetworkManager {
    /**
     * Indicates an unknown access point type
     */
    public static final int ACCESS_POINT_TYPE_UNKNOWN = 1;

    /**
     * Indicates a wlan (802.11b/c/g/n) access point type
     */
    public static final int ACCESS_POINT_TYPE_WLAN = 2;

    /**
     * Indicates an access point based on a cable
     */
    public static final int ACCESS_POINT_TYPE_CABLE = 3;

    /**
     * Indicates a 3g network access point type
     */
    public static final int ACCESS_POINT_TYPE_NETWORK3G = 4;

    /**
     * Indicates a 2g network access point type
     */
    public static final int ACCESS_POINT_TYPE_NETWORK2G = 5;


    /**
     * Indicates a corporate routing server access point type (e.g. BIS etc.)
     */
    public static final int ACCESS_POINT_TYPE_CORPORATE = 6;

    private static final Object LOCK = new Object();
    private static final NetworkManager INSTANCE = new NetworkManager();
    private Vector pending = new Vector();
    private boolean running;
    private int threadCount = 1;
    private NetworkThread[] networkThreads;
    private EventDispatcher errorListeners;
    private EventDispatcher progressListeners;
    private int timeout = 300000;
    private Hashtable threadAssignements = new Hashtable();
    private Hashtable userHeaders;

    private NetworkManager() {
    }

    private boolean handleException(ConnectionRequest r, Exception o) {
        if(errorListeners != null) {
            ActionEvent ev = new NetworkEvent(r, o);
            errorListeners.fireActionEvent(ev);
            return ev.isConsumed();
        }
        return false;
    }

    /**
     * The number of threads
     *
     * @return the threadCount
     */
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * Thread count should never be changed when the network is running since it will have no effect.
     * Increasing the thread count can bring many race conditions and problems to the surface,
     * furthermore MIDP doesn't require support for more than one network thread hence increasing
     * the thread count might fail.
     *
     * @param threadCount the threadCount to set
     */
    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    class NetworkThread implements Runnable {
        private ConnectionRequest currentRequest;
        private Thread threadInstance;

        public NetworkThread() {
        }

        public ConnectionRequest getCurrentRequest() {
            return currentRequest;
        }

        public void start() {
            IOImplementation.getInstance().startThread("Network Thread", this);
        }

        public void interrupt() {
            if(threadInstance != null) {
                threadInstance.interrupt();
            }
        }

        public Thread getThreadInstance() {
            return threadInstance;
        }

        public void run() {
            threadInstance = Thread.currentThread();
            while(running) {
                if(pending.size() > 0) {
                    // the synchronization here isn't essential, only for good measure
                    synchronized(LOCK) {
                        //double lock to prevent a potential exception
                        if(pending.size() == 0){
                            continue;
                        }
                        currentRequest = (ConnectionRequest)pending.elementAt(0);
                        pending.removeElementAt(0);
                        currentRequest.prepare();
                        if(currentRequest.isKilled()){
                            continue;
                        }
                    }
                    if(userHeaders != null) {
                        Enumeration e = userHeaders.keys();
                        while(e.hasMoreElements()) {
                            String key = (String)e.nextElement();
                            String value = (String)userHeaders.get(key);
                            currentRequest.addRequestHeaderDontRepleace(key, value);
                        }
                    }
                    if(threadAssignements.size() > 0) {
                        String n = currentRequest.getClass().getName();
                        Integer threadOffset = (Integer)threadAssignements.get(n);
                        if(threadOffset != null && networkThreads[threadOffset.intValue()] != this) {
                            synchronized(LOCK) {
                                if(pending.size() > 0) {
                                    pending.insertElementAt(currentRequest, 1);
                                    continue;
                                }
                                pending.addElement(currentRequest);
                                LOCK.notify();
                                try {
                                    LOCK.wait(30);
                                } catch (InterruptedException ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                    }

                    int frameRate = -1;
                    try {
                        // for higher priority tasks increase the thread priority, for lower
                        // prioirty tasks decrease it. In critical priority reduce the LWUIT
                        // rendering thread speed for even faster download
                        switch(currentRequest.getPriority()) {
                            case ConnectionRequest.PRIORITY_CRITICAL:
                                frameRate = Display.getInstance().getFrameRate();
                                Display.getInstance().setFramerate(4);
                                Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);
                                break;
                            case ConnectionRequest.PRIORITY_HIGH:
                                Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 2);
                                break;
                            case ConnectionRequest.PRIORITY_NORMAL:
                                break;
                            case ConnectionRequest.PRIORITY_LOW:
                                Thread.currentThread().setPriority(Thread.MIN_PRIORITY + 2);
                                break;
                            case ConnectionRequest.PRIORITY_REDUNDANT:
                                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                                break;
                        }
                        
                        if(progressListeners != null) {
                            progressListeners.fireActionEvent(new NetworkEvent(currentRequest, NetworkEvent.PROGRESS_TYPE_INITIALIZING));
                        }
                        if(currentRequest.getShowOnInit() != null) {
                            currentRequest.getShowOnInit().showModeless();
                        }

                        currentRequest.performOperation();
                    } catch(IOException e) {
                        if(!handleException(currentRequest, e)) {
                            currentRequest.handleIOException(e);
                        }
                    } catch(RuntimeException er) {
                        if(!handleException(currentRequest, er)) {
                            currentRequest.handleRuntimeException(er);
                        }
                    } finally {
                        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                        if(frameRate > -1) {
                            Display.getInstance().setFramerate(frameRate);
                        }

                        if(progressListeners != null) {
                            progressListeners.fireActionEvent(new NetworkEvent(currentRequest, NetworkEvent.PROGRESS_TYPE_COMPLETED));
                        }
                        if(currentRequest.getDisposeOnCompletion() != null) {
                            // there may be a race condition where the dialog hasn't yet appeared but the
                            // network request completed
                            while(Display.getInstance().getCurrent() != currentRequest.getDisposeOnCompletion()) {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException ex) {
                                    ex.printStackTrace();
                                }
                            }
                            currentRequest.getDisposeOnCompletion().dispose();
                        }
                    }
                    currentRequest = null;

                    // wakeup threads waiting for the completion of this network operation
                    synchronized(LOCK) {
                        LOCK.notifyAll();
                    }
                } else {
                    synchronized(LOCK) {
                        try {
                            // prevent waiting when there is still a pending request
                            // this can occur with a race condition since the synchronize
                            // scope is limited to prevent blocking on add...
                            if(pending.size() == 0) {
                                LOCK.wait();
                            }
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    boolean hasProgressListeners() {
        return progressListeners != null;
    }

    void fireProgressEvent(ConnectionRequest c, int type, int length, int sentReceived) {
        // progressListeners might be made null by a separate thread
        EventDispatcher d = progressListeners;
        if(d != null) {
            NetworkEvent n = new NetworkEvent(c, type);
            n.setLength(length);
            n.setSentReceived(sentReceived);
            d.fireActionEvent(n);
        }
    }

    private NetworkThread createNetworkThread() {
        return new NetworkThread();
    }

    /**
     * Invoked to initialize the network thread and start executing elements on the queue
     */
    public void start() {
        if(networkThreads != null) {
            //throw new IllegalStateException("Network manager already initialized");
            return;
        }
        running = true;
        networkThreads = new NetworkThread[getThreadCount()];
        for(int iter = 0 ; iter < getThreadCount() ; iter++) {
            networkThreads[iter] = createNetworkThread();
            networkThreads[iter].start();
        }
        // we need to implement a timeout thread of our own for this case...
        if(!IOImplementation.getInstance().isTimeoutSupported()) {
            IOImplementation.getInstance().startThread("Timeout Thread", new Runnable() {
                public void run() {
                    // detect timeout violations by polling
                    while(running) {
                        try {
                            Thread.sleep(timeout / 10);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }

                        // check for timeout violations on the currently executing threads
                        for(int iter = 0 ; iter < networkThreads.length ; iter++) {
                            ConnectionRequest c = networkThreads[iter].getCurrentRequest();
                            if(c != null) {
                                int cTimeout = Math.min(timeout, c.getTimeout());
                                if(c.getTimeSinceLastActivity() > cTimeout) {
                                    // we have a timeout problem on our hands! We need to try and kill!
                                    c.kill();
                                    networkThreads[iter].interrupt();
                                    try {
                                        Thread.sleep(500);
                                    } catch (InterruptedException ex) {
                                        ex.printStackTrace();
                                    }

                                    // did the attempt work?
                                    if(networkThreads[iter].getCurrentRequest() == c) {
                                        if(c.getTimeSinceLastActivity() > cTimeout) {
                                            // we need to create a whole new network thread and abandon this one!
                                            if(running) {
                                                networkThreads[iter] = createNetworkThread();
                                                networkThreads[iter].start();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * Shuts down the network thread 
     */
    public void shutdown() {
        running = false;
    }

    /**
     * Returns the singleton instance of this class
     * 
     * @return instance of this class
     */
    public static NetworkManager getInstance() {
        return INSTANCE;
    }

    private void addSortedToQueue(ConnectionRequest request, int priority) {
        for(int iter = 0 ; iter < pending.size() ; iter++) {
            ConnectionRequest r = (ConnectionRequest)pending.elementAt(iter);
            if(r.getPriority() < priority) {
                pending.insertElementAt(request, iter);
                return;
            }
        }
        pending.addElement(request);
    }

    /**
     * Adds a header to the global default headers, this header will be implicitly added 
     * to all requests going out from this point onwards. The main use case for this is
     * for authentication information communication via the header.
     * 
     * @param key the key of the header
     * @param value the value of the header
     */
    public void addDefaultHeader(String key, String value) {
        if(userHeaders == null) {
            userHeaders = new Hashtable();
        }
        userHeaders.put(key, value);
    }

    /**
     * Identical to add to queue but waits until the request is processed in the queue,
     * this is useful for completely synchronous operations. 
     * 
     * @param request the request object to add
     */
    public void addToQueueAndWait(final ConnectionRequest request) {
        class WaitingClass implements Runnable, ActionListener {
            private boolean finishedWaiting;
            public void run() {
                while(!finishedWaiting) {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }

            public void actionPerformed(ActionEvent evt) {
                NetworkEvent e = (NetworkEvent)evt;
                 if(e.getConnectionRequest() == request) {
                     if(e.getProgressType() == NetworkEvent.PROGRESS_TYPE_COMPLETED) {
                         finishedWaiting = true;
                         removeProgressListener(this);
                         return;
                     }
                 }
            }
        }
        WaitingClass w = new WaitingClass();
        addProgressListener(w);
        addToQueue(request);
        if(Display.getInstance().isEdt()) {
            Display.getInstance().invokeAndBlock(w);
        } else {
            w.run();
        }
    }

    /**
     * Adds the given network connection to the queue of execution
     *
     * @param request network request for execution
     */
    public void addToQueue(ConnectionRequest request) {
        addToQueue(request, false);
    }

    /**
     * Kills the given request and waits until the request is killed if it is
     * being processed by one of the threads. This method must not be invoked from
     * a network thread or a LWUIT thread!
     * @param request
     */
    public void killAndWait(ConnectionRequest request) {
        request.kill();
        for(int iter = 0 ; iter < threadCount ; iter++) {
            if(networkThreads[iter].currentRequest == request) {
                synchronized(LOCK) {
                    while(networkThreads[iter].currentRequest == request) {
                        try {
                            LOCK.wait(20);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds the given network connection to the queue of execution
     *
     * @param request network request for execution
     */
    void addToQueue(ConnectionRequest request, boolean retry) {
        if(!running) {
            System.out.println("Warning: Network queue wasn't started!");
        }
        request.validateImpl();
        synchronized(LOCK) {
            int i = request.getPriority();
            if(!retry) {
                if(!request.isDuplicateSupported()) {
                    if(pending.contains(request)) {
                        System.out.println("Duplicate entry in the queue: " + request.getClass().getName() + ": " + request);
                        return;
                    }
                    ConnectionRequest currentRequest = networkThreads[0].getCurrentRequest();
                    if(currentRequest != null && currentRequest.equals(request)) {
                        System.out.println("Duplicate entry detected");
                        return;
                    }
                }
            } else {
                i = ConnectionRequest.PRIORITY_HIGH;
            }
            switch(i) {
                case ConnectionRequest.PRIORITY_CRITICAL:
                    pending.insertElementAt(request, 0);
                    ConnectionRequest currentRequest = networkThreads[0].getCurrentRequest();
                    if(currentRequest != null && currentRequest.getPriority() < ConnectionRequest.PRIORITY_CRITICAL) {
                        if(currentRequest.isPausable()) {
                            currentRequest.pause();
                            pending.insertElementAt(currentRequest, 1);
                        } else {
                            currentRequest.kill();
                        }
                    }
                    break;
                case ConnectionRequest.PRIORITY_HIGH:
                case ConnectionRequest.PRIORITY_NORMAL:
                case ConnectionRequest.PRIORITY_LOW:
                case ConnectionRequest.PRIORITY_REDUNDANT:
                    addSortedToQueue(request, i);
                    break;
            }
            LOCK.notify();
        }
    }

    /**
     * Sets the timeout in milliseconds for network connections, a timeout may be "faked"
     * for platforms that don't support the notion of a timeout such as MIDP
     * 
     * @param t the timeout duration
     */
    public void setTimeout(int t) {
        if(IOImplementation.getInstance().isTimeoutSupported()) {
            IOImplementation.getInstance().setTimeout(t);
        } else {
            timeout = t;
        }
    }

    /**
     * Returns the timeout duration
     *
     * @return timeout in milliseconds
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Adds a generic listener to a network error that is invoked before the exception is propogated.
     * Notice that this doesn't apply to server error codes!
     * Consume the event in order to prevent it from propogating further.
     *
     * @param e callback will be invoked with the Exception as the source object
     */
    public void addErrorListener(ActionListener e) {
        if(errorListeners == null) {
            errorListeners = new EventDispatcher();
            errorListeners.setBlocking(true);
        }
        errorListeners.addListener(e);
    }

    /**
     * Removes the given error listener
     *
     * @param e callback to remove
     */
    public void removeErrorListener(ActionListener e) {
        if(errorListeners == null) {
            return;
        }

        errorListeners.removeListener(e);
    }

    /**
     * Adds a listener to be notified when progress updates
     *
     * @param al action listener
     */
    public void addProgressListener(ActionListener al) {
        if(progressListeners == null) {
            progressListeners = new EventDispatcher();
            progressListeners.setBlocking(false);
        }
        progressListeners.addListener(al);
    }

    /**
     * Adds a listener to be notified when progress updates
     *
     * @param al action listener
     */
    public void removeProgressListener(ActionListener al) {
        if(progressListeners == null) {
            return;
        }
        progressListeners.removeListener(al);
        Vector v = progressListeners.getListenerVector();
        if(v == null || v.size() == 0) {
            progressListeners = null;
        }
    }

    /**
     * Makes sure the given class (subclass of ConnectionRequest) is always assigned
     * to the given thread number. This is useful for a case of an application that wants
     * all background downloads to occur on one thread so it doesn't tie up the main
     * network thread (but doesn't stop like a low priority request would).
     *
     * @param requestType the class of the specific connection request
     * @param offset the offset of the thread starting from 0 and smaller than thread count
     */
    public void assignToThread(Class requestType, int offset) {
        threadAssignements.put(requestType.getName(), new Integer(offset));
    }

    /**
     * This method returns all pending ConnectioRequest connections.
     * @return the queue elements
     */
    public Enumeration enumurateQueue(){
        Vector elements = new Vector();
        synchronized(LOCK) {
            Enumeration e = pending.elements();
            while(e.hasMoreElements()){
                elements.addElement(e.nextElement());
            }
        }
        return elements.elements();
    }
}

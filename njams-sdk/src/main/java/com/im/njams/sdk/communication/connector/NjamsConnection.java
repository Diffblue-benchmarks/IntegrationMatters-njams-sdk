/*
 */

package com.im.njams.sdk.communication.connector;

import com.im.njams.sdk.common.NjamsSdkRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NjamsConnection {

    //The logger
    private static final Logger LOG = LoggerFactory.getLogger(NjamsConnection.class);

    //The connection status of the connection instance
    private ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;

    private final Reconnector reconnector;

    private Connector connector;

    private String name;

    public NjamsConnection( Connector connector, String name) {
        this.connector = connector;
        this.reconnector = new Reconnector(this);
        this.name = name;
    }

    /**
     * used to implement your exception handling for this reconnector. Is called if the connection has a problem.
     * It will automatically close any try to reconnect the connection;
     * override this method for your own handling
     *
     * @param exception NjamsSdkRuntimeException
     */
    public void onException(NjamsSdkRuntimeException exception) {
        if (!reconnector.isReconnecting().get()) {
            changeConnectionStatus(ConnectionStatus.ERROR);
            synchronized (this) {
                //This is to determine if this is the first and only reconnection
                if (connectionStatus == ConnectionStatus.ERROR) {
                    // close the existing connection
                    tryToClose();
                    // reconnect
                    Thread reconnect = new Thread(() -> this.reconnector.reconnect(exception));
                    reconnect.setDaemon(true);
                    reconnect.setName(String.format("%s-Reconnector-Thread", connector.getClass().getSimpleName()));
                    reconnect.start();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    //Multiple threads may wait for the lock.
                    //The connection is reconnecting or reconnected already
                }
            }
        }
    }

    public boolean tryToClose() {
        try {
            changeConnectionStatus(ConnectionStatus.DISCONNECTED);
            connector.close();
            return true;
        } catch (Exception e) {
            LOG.debug("Unable to close connection for {}, {}", connector.getClass().getCanonicalName(), e);
            return false;
        }
    }

    /**
     * This method returns whether the reconnector is connected or not.
     *
     * @return true, if it is connected, otherwise false
     */
    public boolean isConnected() {
        return this.connectionStatus == ConnectionStatus.CONNECTED;
    }

    /**
     * This method returns whether the reconnector is disconnected or not.
     *
     * @return true, if it is disconnected, otherwise false
     */
    public boolean isDisconnected() {
        return this.connectionStatus == ConnectionStatus.DISCONNECTED;
    }

    public boolean isStoppingOrStopped() {return this.connectionStatus == ConnectionStatus.STOPPED || this.connectionStatus == ConnectionStatus.STOPPING;}
    /**
     * This method returns whether the reconnector is connecting or not.
     *
     * @return true, if it is connecting, otherwise false
     */
    public boolean isConnecting() {
        return this.connectionStatus == ConnectionStatus.CONNECTING;
    }

    public boolean isError(){
        return this.connectionStatus == ConnectionStatus.ERROR;
    }

    final void tryToConnect() {
        if (!isConnected()) {
            try {
                changeConnectionStatus(ConnectionStatus.CONNECTING);

                connector.connect();

                changeConnectionStatus(ConnectionStatus.CONNECTED);

                Thread.sleep(50);

            } catch (Exception e) {
                tryToClose();
                throw new NjamsSdkRuntimeException("Unable to connect", e);
            }
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name){
        this.name = name;
    }

    public long getReconnectInterval() {
        return reconnector.getReconnectInterval();
    }

    public void initialConnect() {
        String name = this.getName();
        LOG.info("{} connecting...", name);
        try{
            this.tryToConnect();
            LOG.info("{} connected", name);
        }catch(NjamsSdkRuntimeException ex){
            LOG.error("Connection for {} couldn't establish connection. Pushing reconnect task to background.", name, ex);
            onException(ex);
        }
    }

    public void stop(){
        changeConnectionStatus(ConnectionStatus.STOPPING);
        if(reconnector != null) {
            reconnector.stopReconnecting();
        }
        changeConnectionStatus(ConnectionStatus.STOPPED);
        connector = null;
    }

    private synchronized void changeConnectionStatus(ConnectionStatus status){
        if(!isStoppingOrStopped() || status == ConnectionStatus.STOPPING || status == ConnectionStatus.STOPPED){
            this.connectionStatus = status;
        }else{
            LOG.trace("Connection status can't be changed to {}, because status is {}", status, connectionStatus);
        }
    }
}

package com.im.njams.sdk.communication.connection;

import com.im.njams.sdk.Njams;
import com.im.njams.sdk.communication.connectable.Connectable;
import com.im.njams.sdk.communication.pools.ConnectablePool;

import java.util.Properties;

public abstract class NjamsConnectable {

    //The connectablePool where the connectable instances will be safed.
    protected ConnectablePool<Connectable> connectablePool = null;

    public NjamsConnectable(Njams njams, Properties properties) {
        this.connectablePool = setConnectablePool(njams, properties);
        this.init(properties);
    }

    protected abstract <T extends Connectable> ConnectablePool<T> setConnectablePool(Njams njams, Properties properties);

    protected abstract void init(Properties properties);

    public final void stop() {
        stopBeforeConnectablePool();
        if (connectablePool != null) {
            connectablePool.expireAll();
        }
        stopAfterConnectablePool();
    }

    protected void stopAfterConnectablePool() {
        //Do nothing as default
    }

    protected void stopBeforeConnectablePool() {
        //Do nothing as default
    }
}

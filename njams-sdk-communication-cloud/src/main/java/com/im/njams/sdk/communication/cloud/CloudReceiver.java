/* 
 * Copyright (c) 2018 Faiz & Siegeln Software GmbH
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * The Software shall be used for Good, not Evil.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
package com.im.njams.sdk.communication.cloud;

import com.amazonaws.services.iot.client.AWSIotMqttClient;
import com.amazonaws.services.iot.client.AWSIotQos;
import com.im.njams.sdk.communication.AbstractReceiver;
import com.im.njams.sdk.communication.cloud.CertificateUtil.KeyStorePasswordPair;
import java.util.Properties;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pnientiedt
 */
public class CloudReceiver extends AbstractReceiver {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CloudTopic.class);

    private String endpoint;
    private String instanceId;
    private String clientId;
    private String certificateFile;
    private String privateKeyFile;

    private AWSIotQos qos;
    private AWSIotMqttClient mqttclient;
    private String topicName;

    @Override
    public String getName() {
        return CloudConstants.NAME;
    }

    @Override
    public void init(Properties properties) {
        this.endpoint = properties.getProperty(CloudConstants.CLIENT_ENDPOINT);
        if (endpoint == null) {
            LOG.error("Please provide property {} for CloudReceiver", CloudConstants.CLIENT_ENDPOINT);
        }
        this.instanceId = properties.getProperty(CloudConstants.CLIENT_INSTANCEID);
        if (instanceId == null) {
            LOG.error("Please provide property {} for CloudReceiver", CloudConstants.CLIENT_INSTANCEID);
        }
        this.certificateFile = properties.getProperty(CloudConstants.CLIENT_CERTIFICATE);
        if (certificateFile == null) {
            LOG.error("Please provide property {} for CloudReceiver", CloudConstants.CLIENT_CERTIFICATE);
        }
        this.privateKeyFile = properties.getProperty(CloudConstants.CLIENT_PRIVATEKEY);
        if (privateKeyFile == null) {
            LOG.error("Please provide property {} for CloudReceiver", CloudConstants.CLIENT_PRIVATEKEY);
        }
        this.clientId = instanceId + njams.getClientPath().toString().replace(">", "-");
    }

    @Override
    public void start() {
        try {
            LOG.info("Create KeyStorePasswordPair from {} and {}", getCertificateFile(), getPrivateKeyFile());
            KeyStorePasswordPair pair = CertificateUtil.getKeyStorePasswordPair(getCertificateFile(), getPrivateKeyFile());
            if (pair == null) {
                throw new IllegalStateException("Certificate or PrivateKey invalid");
            }
            LOG.info("Connect to endpoint {} with id {}", endpoint, clientId);
            mqttclient = new AWSIotMqttClient(endpoint, clientId, pair.keyStore, pair.keyPassword);
            // optional parameters can be set before connect()
            getMqttclient().connect();
            setQos(AWSIotQos.QOS1);
            topicName = "/" + instanceId + "/commands" + njams.getClientPath().toString().replace(">", "/");
            CloudTopic topic = new CloudTopic(this);
            LOG.info("Topic Subscription: {}", topic.getTopic());
            getMqttclient().subscribe(topic);
        } catch (Exception e) {
            LOG.error("Unable to start CloudReceiver", e);
        }
    }

    @Override
    public void stop() {
        try {
            getMqttclient().disconnect();
        } catch (Exception e) {
            LOG.error("Error disconnecting MQTTClient", e);
        }
    }

    /**
     * @return the qos
     */
    public AWSIotQos getQos() {
        return qos;
    }

    /**
     * @param qos the qos to set
     */
    public void setQos(AWSIotQos qos) {
        this.qos = qos;
    }

    /**
     * @return the mqttclient
     */
    public AWSIotMqttClient getMqttclient() {
        return mqttclient;
    }

    /**
     * @return the topicName
     */
    public String getTopicName() {
        return topicName;
    }

    /**
     * @return the endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * @return the clientId
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * @return the certificateFile
     */
    public String getCertificateFile() {
        return certificateFile;
    }

    /**
     * @return the privateKeyFile
     */
    public String getPrivateKeyFile() {
        return privateKeyFile;
    }

    @Override
    public String getPropertyPrefix() {
        return CloudConstants.PROPERTY_PREFIX;
    }

}

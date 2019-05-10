package com.im.njams.sdk.communication.cloud.connector.receiver;

import com.amazonaws.services.iot.client.*;
import com.im.njams.sdk.Njams;
import com.im.njams.sdk.common.NjamsSdkRuntimeException;
import com.im.njams.sdk.communication.cloud.CloudConstants;
import com.im.njams.sdk.communication.cloud.connector.Endpoints;
import com.im.njams.sdk.communication.cloud.connector.CloudConnector;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public class CloudReceiverConnector extends CloudConnector {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CloudReceiverConnector.class);

    public static final String ON_CONNECT = "/onConnect/";

    public static final AWSIotQos QOS = AWSIotQos.QOS1;

    private UUID uuid = UUID.randomUUID();

    private AWSIotMessage connectionMessage;

    private String endpoint;
    private String instanceId;
    private String clientId;

    private CertificateUtil.KeyStorePasswordPair keyStorePasswordPair;

    private AWSIotMqttClient mqttclient;

    private String topicName;

    private AWSIotTopic topic;


    public CloudReceiverConnector(Properties properties, String name, Njams njams) {
        super(properties, name);

        String payload = getInitialPayload(njams);

        LOG.info("Setting quality of mqtt service to \"at least one delivery\"");
        LOG.info("Creating initial connection message with payload: {}", payload);
        this.connectionMessage = new AWSIotMessage(ON_CONNECT, QOS, payload);

        //Read properties file
        setEndpoint();
        setInstanceId();
        String certificateFile = readCertFile();
        String privateKeyFile = readPrivateKeyFile();

        topicName = createTopicName();

        createKeyStorePasswordPair(certificateFile, privateKeyFile);

        clientId = instanceId + "_" + uuid.toString();
    }

    protected void setEndpoint(){
        try {
            endpoint = getClientEndpoint(properties.getProperty(CloudConstants.ENDPOINT));
        } catch (final Exception ex) {
            throw new NjamsSdkRuntimeException("unable to init cloud receiver", ex);
        }
        if (endpoint == null) {
            printProvidePropertyMessage(CloudConstants.ENDPOINT);
        }
    }

    protected void setInstanceId() {
        instanceId = properties.getProperty(CloudConstants.CLIENT_INSTANCEID);
        if (instanceId == null) {
            printProvidePropertyMessage(CloudConstants.CLIENT_INSTANCEID);
        }
    }

    protected String readPrivateKeyFile() {
        String certificateFile = properties.getProperty(CloudConstants.CLIENT_CERTIFICATE);
        if (certificateFile == null) {
            printProvidePropertyMessage(CloudConstants.CLIENT_CERTIFICATE);
        }
        return certificateFile;
    }

    protected String readCertFile() {
        String privateKeyFile = properties.getProperty(CloudConstants.CLIENT_PRIVATEKEY);
        if (privateKeyFile == null) {
            printProvidePropertyMessage(CloudConstants.CLIENT_PRIVATEKEY);
        }
        return privateKeyFile;
    }

    protected void createKeyStorePasswordPair(String certificateFile, String privateKeyFile) {
        LOG.info("Creating KeyStorePasswordPair from {} and {}", certificateFile, privateKeyFile);
        keyStorePasswordPair
                = CertificateUtil.getKeyStorePasswordPair(certificateFile, privateKeyFile);
        if (keyStorePasswordPair == null) {
            throw new IllegalStateException("Certificate or PrivateKey invalid");
        }
    }

    private void printProvidePropertyMessage(String property){
        LOG.error("Please provide property {} for CloudReceiver", property);
    }

    public void setTopic(AWSIotTopic topic){
        this.topic = topic;
    }

    public String createTopicName() {
        final char sl = '/';
        StringBuilder sb = new StringBuilder();
        sb.append(sl).append(instanceId).
                append(sl).append("commands").
                append(sl).append(uuid.toString()).
                append(sl);
        return sb.toString();
    }

    public String getTopicName() {
        return topicName;
    }

    public String getInitialPayload(Njams njams) {
        final char cbl = '{';
        final char cbr = '}';
        final char dc = ':';
        final char dq = '\"';
        final char c = ',';
        final char sp = ' ';
        StringBuilder sb = new StringBuilder();

        sb.append(cbl).
                append(dq).append("connectionId").append(dq).
                append(dc).
                append(dq).append(uuid.toString()).append(dq).
                append(c).append(sp).

                append(dq).append("instanceId").append(dq).
                append(dc).
                append(dq).append(instanceId).append(dq).
                append(c).append(sp).

                append(dq).append("path").append(dq).
                append(dc).
                append(dq).append(njams.getClientPath().toString()).append(dq).
                append(c).append(sp).

                append(dq).append("clientVersion").append(dq).
                append(dc).
                append(dq).append(njams.getClientVersion()).append(dq).
                append(c).append(sp).

                append(dq).append("sdkVersion").append(dq).
                append(dc).
                append(dq).append(njams.getSdkVersion()).append(dq).
                append(c).append(sp).

                append(dq).append("machine").append(dq).
                append(dc).
                append(dq).append(njams.getMachine()).append(dq).
                append(sp).
        append(cbr);
        return sb.toString();
    }

    @Override
    protected List<Exception> extClose() {
        List<Exception> exceptions = new ArrayList<>();
        if (mqttclient != null) {
            try {
                mqttclient.disconnect();

            } catch (AWSIotException ex) {
                exceptions.add(new NjamsSdkRuntimeException("Unable to disconnect mqttclient correctly", ex));
            } finally {
                mqttclient = null;
            }
        }
        return exceptions;
    }

    @Override
    public void connect() {
        try {
            if (njamsConnection.isConnected()) {
                LOG.warn("Can't connect while being connected.");
                return;
            }
            LOG.debug("Trying to connect to endpoint: {} with clientId: {}", endpoint, clientId);
            createAndConnectMqttClient();
            LOG.info("Connected to endpoint: {} with clientId: {}\", endpoint, clientId");

            String payload = connectionMessage.getStringPayload();
            LOG.debug("Trying to send message: {} to topic: {}", payload, ON_CONNECT);
            publishMessageToTopic();
            LOG.info("Sent message: {} to topic: {}", payload, ON_CONNECT);

            LOG.debug("Trying to subscribe to Topic: {}", topic.getTopic());
            subscribeToTopic();
            LOG.info("Subscribed to topic: {}", topic.getTopic());
        } catch (Exception e) {
            throw new NjamsSdkRuntimeException("Unable to initialize", e);
        }
    }

    private void createAndConnectMqttClient() throws AWSIotException {
        mqttclient = new AWSIotMqttClient(endpoint, clientId, keyStorePasswordPair.keyStore, keyStorePasswordPair.keyPassword);
        mqttclient.connect();
    }

    private void publishMessageToTopic() throws AWSIotException {
        // send initial message
        mqttclient.publish(connectionMessage);
    }

    private void subscribeToTopic() throws AWSIotException {
        // subscribe to topic
        mqttclient.subscribe(topic);
    }
    /**
     * @return the client endpoint
     */
    protected String getClientEndpoint(String endpoint) throws Exception {
        Endpoints endpoints = super.getEndpoints(endpoint);
        return endpoints.client;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public AWSIotMqttClient getMqttClient(){
        return mqttclient;
    }
}

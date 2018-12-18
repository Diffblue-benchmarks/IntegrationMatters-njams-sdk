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
package com.im.njams.sdk.logmessage;

import static java.util.Collections.unmodifiableCollection;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;

import com.faizsiegeln.njams.messageformat.v4.logmessage.ActivityStatus;
import com.faizsiegeln.njams.messageformat.v4.logmessage.LogMessage;
import com.faizsiegeln.njams.messageformat.v4.logmessage.PluginDataItem;
import com.faizsiegeln.njams.messageformat.v4.projectmessage.Extract;
import com.faizsiegeln.njams.messageformat.v4.projectmessage.LogLevel;
import com.faizsiegeln.njams.messageformat.v4.projectmessage.LogMode;
import com.im.njams.sdk.common.DateTimeUtility;
import com.im.njams.sdk.common.NjamsSdkRuntimeException;
import com.im.njams.sdk.common.Path;
import com.im.njams.sdk.configuration.ActivityConfiguration;
import com.im.njams.sdk.configuration.Configuration;
import com.im.njams.sdk.configuration.ProcessConfiguration;
import com.im.njams.sdk.configuration.TracepointExt;
import com.im.njams.sdk.model.ActivityModel;
import com.im.njams.sdk.model.GroupModel;
import com.im.njams.sdk.model.ProcessModel;
import com.im.njams.sdk.model.SubProcessActivityModel;

/**
 * This represents an instance of a process/flow etc in engine to monitor.
 *
 * @author bwand
 */
public class JobImpl implements Job {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Job.class);

    private final ProcessModel processModel;

    private final String jobId;

    private final String logId;
    /*
     * the status of the job
     */
    private JobStatus status;

    /*
     * maximum severity recorded
     */
    private JobStatus maxSeverity;

    // instanceId -> activity
    private final Map<String, Activity> activities = Collections.synchronizedMap(new LinkedHashMap<>());

    /*
     * activity sequence counter
     */
    private final AtomicInteger sequenceCounter;

    /*
     * job level attributes
     */
    private final Map<String, String> attributes;

    /*
     * Plugin data items
     */
    private final List<PluginDataItem> pluginDataItems;

    /*
     * counts how many flushes have been made. Used in LogMessage as messageNo
     */
    private final AtomicInteger flushCounter;

    private Activity startActivity;

    private boolean deepTrace;

    private boolean finished = false;

    private LogMode logMode = LogMode.COMPLETE;
    private LogLevel logLevel = LogLevel.INFO;
    private boolean exclude = false;

    private boolean instrumented;
    private boolean traces;

    private Map<String, ActivityConfiguration> activityConfigurations;

    // internal properties, shall no go to project message
    private final Map<String, Object> properties = new LinkedHashMap<>();

    //1000 for headers and co
    private long estimatedSize = 1000L;

    private boolean recording = true;

    private String correlationLogId;

    private String parentLogId;

    private String externalLogId;

    private String businessService;

    private String businessObject;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime lastFlush;

    private LocalDateTime businessEnd;

    private LocalDateTime businessStart;

    /**
     * Create a job with a givenModelId, a jobId and a logId
     *
     * @param processModel for job to create
     * @param jobId of Job to create
     * @param logId of Job to create
     */
    public JobImpl(ProcessModel processModel, String jobId, String logId) {
        this.jobId = jobId;
        this.logId = logId;
        correlationLogId = logId;
        maxSeverity = JobStatus.CREATED;
        this.processModel = processModel;
        status = JobStatus.CREATED;
        sequenceCounter = new AtomicInteger();
        flushCounter = new AtomicInteger();
        lastFlush = DateTimeUtility.now();
        attributes = new HashMap<>();
        pluginDataItems = new ArrayList<>();
        initFromConfiguration();
    }

    private LogMessage createLogMessage(JobImpl job) {
        LogMessage logMessage = new LogMessage();
        logMessage.setBusinessEnd(businessEnd);
        logMessage.setBusinessStart(businessStart);
        logMessage.setCategory(processModel.getNjams().getCategory());
        logMessage.setCorrelationLogId(correlationLogId);
        logMessage.setExternalLogId(externalLogId);
        logMessage.setJobEnd(endTime);
        logMessage.setJobId(jobId);
        logMessage.setJobStart(startTime);
        logMessage.setLogId(logId);
        logMessage.setMachineName(processModel.getNjams().getMachine());
        logMessage.setMaxSeverity(maxSeverity.getValue());
        logMessage.setMessageNo(job.flushCounter.get());
        logMessage.setObjectName(businessObject);
        logMessage.setParentLogId(parentLogId);
        logMessage.setPath(processModel.getPath().toString());
        logMessage.setProcessName(processModel.getName());
        logMessage.setStatus(status.getValue());
        logMessage.setServiceName(businessService);
        attributes.entrySet().forEach(e -> logMessage.addAtribute(e.getKey(), e.getValue()));
        pluginDataItems.forEach(i -> logMessage.addPluginDataItem(i));
        return logMessage;
    }

    /**
     * Creates ActivityBuilder with a given activityModeId.
     *
     * @param activityModelId to create
     * @return a builder
     */
    @Override
    public ActivityBuilder createActivity(String activityModelId) {
        return new ActivityBuilder(this, activityModelId);
    }

    /**
     * Creates ActivityBuilder with a given ActivityModel.
     *
     * @param activityModel to create
     * @return a builder
     */
    @Override
    public ActivityBuilder createActivity(ActivityModel activityModel) {
        if (activityModel instanceof GroupModel) {
            return createGroup((GroupModel) activityModel);
        }
        if (activityModel instanceof SubProcessActivityModel) {
            return createSubProcess((SubProcessActivityModel) activityModel);
        }
        return new ActivityBuilder(this, activityModel);
    }

    /**
     * Creates GroupBuilder with a given groupModelId.
     *
     * @param groupModelId to create
     * @return a builder
     */
    @Override
    public GroupBuilder createGroup(String groupModelId) {
        return new GroupBuilder(this, groupModelId);
    }

    /**
     * Creates GroupBuilder with a given GroupModel.
     *
     * @param groupModel to create
     * @return a builder
     */
    @Override
    public GroupBuilder createGroup(GroupModel groupModel) {
        return new GroupBuilder(this, groupModel);
    }

    /**
     * Creates SubProcessBuilder with a given SubProcessModel.
     *
     * @param groupModel to create
     * @return a builder
     */
    @Override
    public SubProcessActivityBuilder createSubProcess(SubProcessActivityModel groupModel) {
        return new SubProcessActivityBuilder(this, groupModel);
    }

    /**
     * Adds a new Activity to the Job.
     *
     * @param activity to add
     */
    @Override
    public void addActivity(final Activity activity) {
        activities.put(activity.getInstanceId(), activity);
        if (activity.isStarter()) {
            if (startActivity != null) {
                throw new NjamsSdkRuntimeException("A job must not have more than one start activity");
            }
            startActivity = activity;
        }
    }

    /**
     * Returns a activity to a given instanceId.
     *
     * @param activityInstanceId to get
     * @return the {@link Activity}
     */
    @Override
    public Activity getActivityByInstanceId(String activityInstanceId) {
        return activities.get(activityInstanceId);
    }

    /**
     * Returns the last added activity to a given modelId.
     *
     * @param activityModelId to get
     * @return the {@link Activity}
     */
    @Override
    public Activity getActivityByModelId(String activityModelId) {
        synchronized (activities) {
            List<String> reverseOrderedKeys = new ArrayList<>(activities.keySet());
            ListIterator<String> iterator = reverseOrderedKeys.listIterator(reverseOrderedKeys.size());
            while (iterator.hasPrevious()) {
                Activity _activity = activities.get(iterator.previous());
                if (_activity != null && _activity.getModelId().equals(activityModelId)) {
                    return _activity;
                }
            }
            return null;
        }
    }

    /**
     * Returns the last added and running activity to a given modelId.
     *
     * @param activityModelId to get
     * @return the {@link Activity}
     */
    @Override
    public Activity getRunningActivityByModelId(String activityModelId) {
        synchronized (activities) {
            List<String> reverseOrderedKeys = new ArrayList<>(activities.keySet());
            ListIterator<String> iterator = reverseOrderedKeys.listIterator(reverseOrderedKeys.size());
            while (iterator.hasPrevious()) {
                Activity _activity = activities.get(iterator.previous());
                if (_activity.getActivityStatus() == ActivityStatus.RUNNING
                        && _activity.getModelId().equals(activityModelId)) {
                    return _activity;
                }
            }
            return null;
        }
    }

    /**
     * Returns the last added and completed activity to a given modelId.
     *
     * @param activityModelId to get
     * @return the {@link Activity}
     */
    @Override
    public Activity getCompletedActivityByModelId(String activityModelId) {
        synchronized (activities) {
            List<String> reverseOrderedKeys = new ArrayList<>(activities.keySet());
            ListIterator<String> iterator = reverseOrderedKeys.listIterator(reverseOrderedKeys.size());
            while (iterator.hasPrevious()) {
                Activity _activity = activities.get(iterator.previous());
                if (_activity.getActivityStatus().ordinal() > ActivityStatus.RUNNING.ordinal()
                        && _activity.getModelId().equals(activityModelId)) {
                    return _activity;
                }
            }
            return null;
        }
    }

    /**
     * Return the start activity
     *
     * @return the start activity
     */
    @Override
    public Activity getStartActivity() {
        return startActivity;
    }

    /**
     * Return all Activities
     *
     * @return all Activities
     */
    @Override
    public Collection<Activity> getActivities() {
        return unmodifiableCollection(activities.values());
    }

    /**
     * Returns the next Sequence for the next executed Activity.
     *
     * @return the next one
     */
    long getNextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    /**
     * Flush this
     */
    public synchronized void flush() {
        if (mustBeSuppressed()) {
            return;
        }
        flushCounter.incrementAndGet();
        lastFlush = DateTimeUtility.now();
        LogMessage logMessage = createLogMessage(this);
        if (status.getValue() < JobStatus.RUNNING.getValue()) {
            return;
        }
        addToLogMessageAndCleanup(logMessage);
        logMessage.setSentAt(lastFlush);
        processModel.getNjams().getSender().send(logMessage);
        // clean up jobImpl
        attributes.clear();
        pluginDataItems.clear();
        calculateEstimatedSize();
    }

    /**
     * Starts the job, i.e., sets the according status, job start date if not
     * set before, and flags the job to begin flushing.
     */
    @Override
    public void start() {
        setStartTime(DateTimeUtility.now());
        setStatus(JobStatus.RUNNING);
    }

    /**
     * Set the logMessage status to success if the status is running. Then flush
     * it. 
     * If the job has been finished before or if the job hasn't been started before
     * it is finished, an NjamsSdkRuntimeException is thrown.
     */
    @Override
    public void end() {
        if (finished) {
            throw new NjamsSdkRuntimeException("Job already finished");
        }
        else if(this.startTime == null){
            throw new NjamsSdkRuntimeException("Job can't be finished before it started (Call job.start() before!).");
        }
        synchronized (activities) {
            //end all not ended activities
            activities.values().stream()
                    .filter(a -> a.getActivityStatus() == null || a.getActivityStatus() == ActivityStatus.RUNNING)
                    .forEach(a -> a.end());

            if (getStatus().getValue() <= JobStatus.RUNNING.getValue()) {
                setStatus(JobStatus.SUCCESS);
            }
            if (getEndTime() == null) {
                setEndTime(DateTimeUtility.now());
            }
            flush();
            finished = true;
            processModel.getNjams().removeJob(getJobId());
        }
    }

    /**
     * Sets a status for this {@link Job}. Also set the maxSeverityStatus if it
     * is not set or lower than the status
     *
     * @param status the status to set
     */
    @Override
    public void setStatus(JobStatus status) {
        this.status = status == null ? JobStatus.CREATED : status;
        if (maxSeverity == null || maxSeverity.getValue() < this.status.getValue()) {
            maxSeverity = status;
        }
    }

    /**
     * Gets a status for this {@link Job}
     *
     * @return the status
     */
    @Override
    public JobStatus getStatus() {
        return status;
    }

    /**
     * Return the ProcessModel
     *
     * @return the ProcessModel
     */
    @Override
    public ProcessModel getProcessModel() {
        return processModel;
    }

    /**
     * Sets the correlation log id of this job.
     *
     * @param correlationLogId collreation log id
     */
    @Override
    public void setCorrelationLogId(final String correlationLogId) {
        this.correlationLogId = correlationLogId;
    }

    /**
     * Returns the correlation log id of this job.
     *
     * @return collreation log id
     */
    @Override
    public String getCorrelationLogId() {
        return correlationLogId;
    }

    /**
     * Set the parentLogId
     *
     * @param parentLogId parentLogId to set
     */
    @Override
    public void setParentLogId(String parentLogId) {
        this.parentLogId = parentLogId;
    }

    /**
     * Return the parentLogId
     *
     * @return the parentLogId
     */
    @Override
    public String getParentLogId() {
        return parentLogId;
    }

    /**
     * Set the externalLogId
     *
     * @param externalLogId texternalLogId to set
     */
    @Override
    public void setExternalLogId(String externalLogId) {
        this.externalLogId = externalLogId;
    }

    /**
     * Return the externalLogId
     *
     * @return the externalLogId
     */
    @Override
    public String getExternalLogId() {
        return externalLogId;
    }

    /**
     * Set the businessService as String
     *
     * @param businessService businessService to set
     */
    @Override
    public void setBusinessService(String businessService) {
        setBusinessService(new Path(businessService));
    }

    /**
     * Set the businessService as Path
     *
     * @param businessService businessService to set
     */
    @Override
    public void setBusinessService(Path businessService) {
        if (businessService != null) {
            this.businessService = businessService.toString();
        }
    }

    /**
     * Return the businessService
     *
     * @return the businessService
     */
    @Override
    public String getBusinessService() {
        return businessService;
    }

    /**
     * Set the businessObject as String
     *
     * @param businessObject businessObject to set
     */
    @Override
    public void setBusinessObject(String businessObject) {
        setBusinessObject(new Path(businessObject));
    }

    /**
     * Set the binsessObject as Path
     *
     * @param businessObject businessObject to set
     */
    @Override
    public void setBusinessObject(Path businessObject) {
        if (businessObject != null) {
            this.businessObject = businessObject.toString();
        }
    }

    /**
     * Return the businessObject
     *
     * @return the businessObject
     */
    @Override
    public String getBusinessObject() {
        return businessObject;
    }

    /**
     * Return the startTime
     *
     * @return the startTime
     */
    @Override
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /**
     * Sets the start timestamp of a job. <br>
     * <b>CAUTION:</b> <br>
     * This method must not be called after the job has been started. If you
     * need to set the job start explicitly, set it before you call {@link #start()
     * }. if you don't set the job start explicitly, it is set to the timestamp
     * ob the job creation.
     *
     * @param jobStart job start
     */
    @Override
    public void setStartTime(final LocalDateTime jobStart) {
        if (status == JobStatus.CREATED) {
            startTime = jobStart;
        } else {
            throw new NjamsSdkRuntimeException("job start time must not be set after job is started!");
        }
    }

    /**
     * Sets the end timestamp of a job.
     *
     * @param jobEnd job end
     */
    @Override
    public void setEndTime(final LocalDateTime jobEnd) {
        endTime = jobEnd;
    }

    /**
     * Return the endTime
     *
     * @return the endTime
     */
    @Override
    public LocalDateTime getEndTime() {
        return endTime;
    }

    /**
     * Gets the maximal severity of this job job.
     *
     * @return max severity
     */
    @Override
    public JobStatus getMaxSeverity() {
        return maxSeverity;
    }

    /**
     * Indicates whether the job is already finished or not.
     *
     * @return <b>true</b> if and only if the job is already finished, else
     * <b>false</b>
     */
    @Override
    public boolean isFinished() {
        return getStatus() == JobStatus.CREATED || getStatus() == JobStatus.RUNNING;
    }

    /**
     * Set an Attribute to the job
     *
     * @param name attribute name
     * @param value attribute value
     */
    @Override
    public void setAttribute(final String name, final String value) {
        attributes.put(name, value);
    }

    /**
     * Return the Attribute name to a given value
     *
     * @param name attribute name
     * @return attribute value
     */
    @Override
    public String getAttribute(final String name) {
        return attributes.get(name);
    }

    /**
     * Return all attributes for this job
     *
     * @return unmodifiable list of attributes
     */
    @Override
    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Return if the job contains a attribute for a given name
     *
     * @param name attribute name to check
     * @return true if found, false if not found
     */
    @Override
    public boolean hasAttribute(final String name) {
        return attributes.containsKey(name);
    }

    /**
     * Marks that a job shall collect trace information for each activity
     * (including sub processes).
     *
     * @param deepTrace
     * <b>true</b> if deep trace shall be activiated.
     */
    @Override
    public void setDeepTrace(boolean deepTrace) {
        this.deepTrace = deepTrace;
    }

    /**
     * Indicates that trace information shall be collected for all activites of
     * this job (including sub processes).
     *
     * @return <b>true</b> if and only if deep trace is enabled.
     */
    @Override
    public boolean isDeepTrace() {
        return deepTrace;
    }

    /**
     * Return the last push LocalDateTime
     *
     * @return the last push LocalDateTime
     */
    public LocalDateTime getLastFlush() {
        return lastFlush;
    }

    private boolean mustBeSuppressed() {
        synchronized (activities) {
            // Do not send if one of the conditions is true.
            if (isLogModeNone() || isLogModeExclusiveAndNotInstrumented() || isExcludedProcess()
                    || isLogLevelHigherAsJobStateAndHasNoTraces()) {
                LOG.debug("Job not flushed: Engine Mode: {} // Job's log level: {}, "
                        + "configured level: {} // is excluded: {} // has traces: {}", logMode, getStatus(), logLevel,
                        exclude, traces);
                //delete not running activities
                removeNotRunningActivities();
                calculateEstimatedSize();
                LOG.debug("mustBeSuppressed: true");
                return true;
            } else {
                LOG.debug("mustBeSuppressed: false");
                return false;
            }
        }
    }

    private boolean isLogModeNone() {
        if (logMode == LogMode.NONE) {
            LOG.debug("isLogModeNone: true");
            return true;
        } else {
            return false;
        }
    }

    private boolean isLogModeExclusiveAndNotInstrumented() {
        if (logMode == LogMode.EXCLUSIVE && !isInstrumented()) {
            LOG.debug("isLogModeExclusiveAndNotInstrumented: true");
            return true;
        } else {
            return false;
        }
    }

    private boolean isExcludedProcess() {
        if (exclude) {
            LOG.debug("isExcludedProcess: true");
            return true;
        } else {
            return false;
        }
    }

    private boolean isLogLevelHigherAsJobStateAndHasNoTraces() {
        LOG.debug("{} : {}, {}", getStatus(), logLevel.value(), traces);
        if (status.getValue() < logLevel.value() && !traces) {
            LOG.debug("isLogLevelHigherAsJobStateAndHasNoTraces: true");
            return true;
        } else {
            return false;
        }
    }

    private boolean isInstrumented() {
        return instrumented;
    }

    /**
     * @param instrumented the instrumented to set
     */
    void setInstrumented(boolean instrumented) {
        this.instrumented = instrumented;
    }

    private void initFromConfiguration() {
        Configuration configuration = processModel.getNjams().getConfiguration();
        if (configuration == null) {
            LOG.error("Unable to set LogMode, LogLevel and Exclude for {}, configuration is null",
                    processModel.getPath());
            return;
        }
        logMode = configuration.getLogMode();
        LOG.debug("Set LogMode for {} to {}", processModel.getPath(), logMode);

        recording = configuration.isRecording();
        LOG.debug("Set recording for {} to {} based on client settings", processModel.getPath(), recording);

        ProcessConfiguration process = configuration.getProcess(processModel.getPath().toString());
        if (process != null) {
            logLevel = process.getLogLevel();
            LOG.debug("Set LogLevel for {} to {}", processModel.getPath(), logLevel);
            exclude = process.isExclude();
            LOG.debug("Set Exclude for {} to {}", processModel.getPath(), exclude);
            recording = process.isRecording();
            LOG.debug("Set recording for {} to {} based on process settings {} and client setting {}",
                    processModel.getPath(), recording, configuration.isRecording());
            activityConfigurations = process.getActivities();
        }
    }

    TracepointExt getTracepoint(String modelId) {
        if (activityConfigurations != null) {
            ActivityConfiguration activityConfiguration = activityConfigurations.get(modelId);
            if (activityConfiguration != null) {
                return activityConfiguration.getTracepoint();
            }
        }
        return null;
    }

    Extract getExtract(String modelId) {
        if (activityConfigurations != null) {
            ActivityConfiguration settings = activityConfigurations.get(modelId);
            if (settings != null) {
                return settings.getExtract();
            }
        }
        return null;
    }

    /**
     * @return the traces
     */
    @Override
    public boolean isTraces() {
        return traces;
    }

    /**
     * @param traces the traces to set
     */
    public void setTraces(boolean traces) {
        this.traces = traces;
    }

    /**
     * Gets a properties value. Properties will not be send within project
     * messages.
     *
     * @param key name of the property
     * @return Properties value of <b>null</b>
     */
    @Override
    public Object getProperty(final String key) {
        return properties.get(key);
    }

    /**
     * Checks whether the activity has a property with a given name.
     *
     * @param key name of the property
     * @return <b>true</b> if and only if a property with the given name exists.
     */
    @Override
    public boolean hasProperty(final String key) {
        return properties.containsKey(key);
    }

    /**
     * Sets a properties value. Properties will not be send within project
     * messages.
     *
     * @param key name of the property
     * @param value value of the property
     */
    @Override
    public void setProperty(final String key, final Object value) {
        properties.put(key, value);
    }

    /**
     * Removes the property with a given name
     *
     * @param key name of the property
     * @return Previous value of the property (if it existed) or else
     * <b>null</b>.
     */
    @Override
    public Object removeProperty(final String key) {
        return properties.remove(key);
    }

    private void addToLogMessageAndCleanup(LogMessage logMessage) {
        synchronized (activities) {
            //add all to logMessage
            activities.values().forEach(logMessage::addActivity);
            //remove finished
            removeNotRunningActivities();
        }
    }

    /**
     * This method removes all not running activities from the activities map if
     * the activity has a parent, remove the activity from the childActivity map
     * of the parent.
     */
    private void removeNotRunningActivities() {
        int loggingSum = 0;
        synchronized (activities) {
            Iterator<Activity> iterator = activities.values().iterator();
            while (iterator.hasNext()) {
                Activity a = iterator.next();
                if (a.getActivityStatus() != ActivityStatus.RUNNING) {
                    loggingSum++;
                    iterator.remove();
                    GroupImpl parent = (GroupImpl) a.getParent();
                    if (parent != null) {
                        parent.removeChildActivity(a.getInstanceId());
                    }
                }

            }
            LOG.trace("{} activities have been removed from {}. Still running: {}", loggingSum, getLogId(),
                    activities.size());
        }
    }

    /**
     * @return the estimatedSize
     */
    public long getEstimatedSize() {
        return estimatedSize;
    }

    /**
     * Add estimatedSize to the estimatedSize of the activity
     *
     * @param estimatedSize estimatedSize to add
     */
    public void addToEstimatedSize(long estimatedSize) {
        this.estimatedSize += estimatedSize;
    }

    private void calculateEstimatedSize() {
        synchronized (activities) {
            estimatedSize =
                    1000 + activities.values().stream().mapToLong(a -> ((ActivityImpl) a).getEstimatedSize()).sum();
        }
    }

    /**
     * Returns true if a Activity for a given activityModelId needs input or
     * output data, based on extracts and tracepoints
     *
     * @param activityModelId activityModelId to check
     * @return boolean if true
     */
    @Override
    public boolean needsData(String activityModelId) {
        if (deepTrace) {
            return true;
        }
        if (activityConfigurations != null && activityConfigurations.containsKey(activityModelId)) {
            ActivityConfiguration settings = activityConfigurations.get(activityModelId);
            return settings.getExtract() != null || settings.getTracepoint() != null;
        }
        return false;
    }

    /**
     * Return if recording is activated for this job
     *
     * @return true if activated, false if not
     */
    public boolean isRecording() {
        return recording;
    }

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public String getLogId() {
        return logId;
    }

    @Override
    public void addPluginDataItem(
            com.faizsiegeln.njams.messageformat.v4.logmessage.interfaces.IPluginDataItem pluginDataItem) {
        pluginDataItems.add((PluginDataItem) pluginDataItem);
    }

    @Override
    public void addAtribute(String key, String value) {
        attributes.put(key, value);
    }
}

package com.sap.cloud.lm.sl.cf.process.steps;

import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;

import org.cloudfoundry.client.lib.CloudControllerClient;
import org.cloudfoundry.client.lib.CloudOperationException;
import org.cloudfoundry.client.lib.StartingInfo;
import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.cloudfoundry.client.lib.domain.CloudApplication.State;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.sap.cloud.lm.sl.cf.core.cf.clients.RecentLogsRetriever;
import com.sap.cloud.lm.sl.cf.core.util.ApplicationConfiguration;
import com.sap.cloud.lm.sl.cf.process.Constants;
import com.sap.cloud.lm.sl.cf.process.message.Messages;

@Component("restartAppStep")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class RestartAppStep extends TimeoutAsyncFlowableStep {

    @Autowired
    protected RecentLogsRetriever recentLogsRetriever;
    @Autowired
    protected RecentLogsRetriever recentLogsRetrieverFailSafe;
    @Autowired
    protected ApplicationConfiguration configuration;

    @Override
    public StepPhase executeAsyncStep(ExecutionWrapper execution) {
        CloudApplication app = getAppToRestart(execution.getContext());
        CloudControllerClient client = execution.getControllerClient();

        if (isStarted(client, app.getName())) {
            stopApp(client, app);
        }
        StartingInfo startingInfo = startApp(client, app);
        setStartupPollingInfo(execution.getContext(), startingInfo);
        return StepPhase.POLL;
    }

    @Override
    protected String getStepErrorMessage(DelegateExecution context) {
        return MessageFormat.format(Messages.ERROR_STARTING_APP_1, getAppToRestart(context).getName());
    }

    protected CloudApplication getAppToRestart(DelegateExecution context) {
        return StepsUtil.getApp(context);
    }

    private void setStartupPollingInfo(DelegateExecution context, StartingInfo startingInfo) {
        StepsUtil.setStartingInfo(context, startingInfo);
        if (context.getVariable(Constants.VAR_START_TIME) == null) {
            context.setVariable(Constants.VAR_START_TIME, System.currentTimeMillis());
        }
        if (context.getVariable(Constants.VAR_OFFSET) == null) {
            context.setVariable(Constants.VAR_OFFSET, 0);
        }
    }

    private boolean isStarted(CloudControllerClient client, String appName) {
        try {
            CloudApplication app = client.getApplication(appName);
            return app.getState()
                      .equals(State.STARTED);
        } catch (CloudOperationException e) {
            if (e.getStatusCode()
                 .equals(HttpStatus.INTERNAL_SERVER_ERROR)) {
                logger.warn(e.getMessage(), e);
                return false;
            }
            throw e;
        }
    }

    private void stopApp(CloudControllerClient client, CloudApplication app) {
        getStepLogger().info(Messages.STOPPING_APP, app.getName());
        client.stopApplication(app.getName());
    }

    private StartingInfo startApp(CloudControllerClient client, CloudApplication app) {
        getStepLogger().info(Messages.STARTING_APP, app.getName());
        return client.startApplication(app.getName());
    }

    @Override
    protected List<AsyncExecution> getAsyncStepExecutions(ExecutionWrapper execution) {
        List<AsyncExecution> stepExecutions = new LinkedList<>();

        recentLogsRetrieverFailSafe.setFailSafe(true);
        stepExecutions.add(new PollStartAppStatusExecution(recentLogsRetrieverFailSafe));
        stepExecutions.add(new PollExecuteAppStatusExecution(recentLogsRetriever));
        return stepExecutions;
    }

    @Override
    public Integer getTimeout(DelegateExecution context) {
        return StepsUtil.getInteger(context, Constants.PARAM_START_TIMEOUT, Constants.DEFAULT_START_TIMEOUT);
    }

}

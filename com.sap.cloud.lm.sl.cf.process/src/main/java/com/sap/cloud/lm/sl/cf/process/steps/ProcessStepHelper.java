package com.sap.cloud.lm.sl.cf.process.steps;

import com.sap.cloud.lm.sl.cf.core.model.ErrorType;
import com.sap.cloud.lm.sl.cf.persistence.model.ProgressMessage;
import com.sap.cloud.lm.sl.cf.persistence.model.ProgressMessage.ProgressMessageType;
import com.sap.cloud.lm.sl.cf.persistence.services.ProcessLogsPersister;
import com.sap.cloud.lm.sl.cf.persistence.services.ProgressMessageService;
import com.sap.cloud.lm.sl.cf.process.Constants;
import com.sap.cloud.lm.sl.cf.process.message.Messages;
import com.sap.cloud.lm.sl.cf.process.util.StepLogger;
import com.sap.cloud.lm.sl.common.ContentException;
import com.sap.cloud.lm.sl.common.SLException;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.runtime.Execution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.List;
import java.util.stream.Collectors;

public class ProcessStepHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessStepHelper.class);

    private ProgressMessageService progressMessageService;
    private ProcessLogsPersister processLogsPersister;
    private StepLogger stepLogger;

    private ProcessEngineConfiguration processEngineConfiguration;

    public ProcessStepHelper(ProgressMessageService progressMessageService, StepLogger stepLogger,
        ProcessLogsPersister processLogsPersister, ProcessEngineConfiguration processEngineConfigurationSupplier) {
        this.progressMessageService = progressMessageService;
        this.stepLogger = stepLogger;
        this.processLogsPersister = processLogsPersister;
        this.processEngineConfiguration = processEngineConfigurationSupplier;
    }

    protected void postExecuteStep(DelegateExecution context, StepPhase state) {
        logDebug(MessageFormat.format(Messages.STEP_FINISHED, context.getCurrentFlowElement()
            .getName()));

        processLogsPersister.persistLogs(StepsUtil.getCorrelationId(context), StepsUtil.getTaskId(context));
        context.setVariable(Constants.VAR_STEP_EXECUTION, state.toString());
    }

    void preExecuteStep(DelegateExecution context, StepPhase initialPhase) {
        String taskId = context.getCurrentActivityId();
        context.setVariable(Constants.TASK_ID, taskId);

        deletePreviousErrorType(context);
        logTaskStartup(context, taskId);
        StepsUtil.setStepPhase(context, initialPhase);
    }

    protected void deletePreviousErrorType(DelegateExecution context) {
        String processId = context.getProcessInstanceId();
        ErrorType errorType = StepsUtil.getErrorType(context);
        if (errorType == null) {
            return;
        }
        LOGGER.debug(MessageFormat.format(Messages.DELETING_ERROR_TYPE_O_FOR_PROCESS_1, errorType, processId));
        context.removeVariable(Constants.VAR_ERROR_TYPE);
    }

    private void logTaskStartup(DelegateExecution context, String taskId) {
        stepLogger.logFlowableTask();
        String message = MessageFormat.format(Messages.EXECUTING_TASK, context.getCurrentActivityId(), context.getProcessInstanceId());
        progressMessageService.add(new ProgressMessage(StepsUtil.getCorrelationId(context), taskId, ProgressMessageType.TASK_STARTUP, message,
            new Timestamp(System.currentTimeMillis())));
    }

    protected void logException(DelegateExecution context, Throwable t) {
        LOGGER.error(Messages.EXCEPTION_CAUGHT, t);
        stepLogger.errorWithoutProgressMessage(Messages.EXCEPTION_CAUGHT, t);

        storeExceptionInProgressMessageService(context, t);

        if (t instanceof ContentException) {
            StepsUtil.setErrorType(context, ErrorType.CONTENT_ERROR);
        } else {
            StepsUtil.setErrorType(context, ErrorType.UNKNOWN_ERROR);
        }
    }

    public void storeExceptionInProgressMessageService(DelegateExecution context, Throwable t) {
        try {
            ProgressMessage msg = new ProgressMessage(StepsUtil.getCorrelationId(context), getCurrentActivityId(context), ProgressMessageType.ERROR,
                MessageFormat.format(Messages.UNEXPECTED_ERROR, t.getMessage()), new Timestamp(System.currentTimeMillis()));
            progressMessageService.add(msg);
        } catch (SLException e) {
            stepLogger.errorWithoutProgressMessage(Messages.SAVING_ERROR_MESSAGE_FAILED, e);
        }
    }

    // This method is needed because sometimes the DelegateExecution::getCurrentActivityId returns null
    // Check the issue: https://github.com/flowable/flowable-engine/issues/1280
    private String getCurrentActivityId(DelegateExecution context) {
        List<Execution> processExecutions = processEngineConfiguration.getRuntimeService()
            .createExecutionQuery()
            .processInstanceId(context.getProcessInstanceId())
            .list();
        List<Execution> processExecutionsWithActivityIds = processExecutions.stream()
            .filter(e -> e.getActivityId() != null)
            .collect(Collectors.toList());
        if (processExecutionsWithActivityIds.isEmpty()) {
            // if this happen then there is a really big problem with Flowable :)
            throw new SLException("There are no executions for process with id: " + context.getProcessInstanceId());
        }
        return processExecutionsWithActivityIds.get(0)
            .getActivityId();
    }

    private void logDebug(String message) {
        stepLogger.debug(message);
    }


    public void failStepIfProcessIsAborted(DelegateExecution context) {
        Boolean processAborted = (Boolean) processEngineConfiguration.getRuntimeService()
            .getVariable(StepsUtil.getCorrelationId(context), Constants.PROCESS_ABORTED);
        if (processAborted != null && processAborted) {
            throw new SLException(Messages.PROCESS_WAS_ABORTED);
        }
    }

}

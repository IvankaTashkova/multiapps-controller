package com.sap.cloud.lm.sl.cf.process.steps;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.cloudfoundry.client.lib.CloudControllerClient;
import org.cloudfoundry.client.lib.CloudOperationException;
import org.cloudfoundry.client.lib.domain.CloudService;
import org.cloudfoundry.client.lib.domain.CloudServiceKey;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.sap.cloud.lm.sl.cf.client.lib.domain.CloudServiceExtended;
import com.sap.cloud.lm.sl.cf.client.lib.domain.ImmutableCloudServiceExtended;
import com.sap.cloud.lm.sl.cf.core.cf.clients.ServiceGetter;
import com.sap.cloud.lm.sl.cf.core.cf.services.ServiceOperation;
import com.sap.cloud.lm.sl.cf.core.cf.services.ServiceOperationState;
import com.sap.cloud.lm.sl.cf.core.cf.services.ServiceOperationType;
import com.sap.cloud.lm.sl.cf.core.cf.v2.ResourceType;
import com.sap.cloud.lm.sl.cf.core.helpers.MtaArchiveElements;
import com.sap.cloud.lm.sl.cf.core.security.serialization.SecureSerializationFacade;
import com.sap.cloud.lm.sl.cf.core.util.ApplicationConfiguration;
import com.sap.cloud.lm.sl.cf.persistence.processors.DefaultFileDownloadProcessor;
import com.sap.cloud.lm.sl.cf.persistence.services.FileContentProcessor;
import com.sap.cloud.lm.sl.cf.persistence.services.FileStorageException;
import com.sap.cloud.lm.sl.cf.process.Constants;
import com.sap.cloud.lm.sl.cf.process.analytics.model.ServiceAction;
import com.sap.cloud.lm.sl.cf.process.message.Messages;
import com.sap.cloud.lm.sl.common.SLException;
import com.sap.cloud.lm.sl.common.util.CommonUtil;
import com.sap.cloud.lm.sl.common.util.JsonUtil;
import com.sap.cloud.lm.sl.mta.handlers.ArchiveHandler;
import com.sap.cloud.lm.sl.mta.util.PropertiesUtil;

@Component("determineServiceCreateUpdateActionsStep")
public class DetermineServiceCreateUpdateServiceActionsStep extends SyncFlowableStep {

    @Inject
    private ServiceGetter serviceInstanceGetter;

    @Inject
    private ApplicationConfiguration configuration;

    private SecureSerializationFacade secureSerializer = new SecureSerializationFacade();

    @Override
    protected StepPhase executeStep(ExecutionWrapper execution) throws Exception {
        CloudControllerClient controllerClient = execution.getControllerClient();
        String spaceId = StepsUtil.getSpaceId(execution.getContext());
        CloudServiceExtended serviceToProcess = StepsUtil.getServiceToProcess(execution.getContext());

        execution.getStepLogger()
                 .info(Messages.PROCESSING_SERVICE, serviceToProcess.getName());
        CloudService existingService = controllerClient.getService(serviceToProcess.getName(), false);

        Map<String, List<CloudServiceKey>> serviceKeys = StepsUtil.getServiceKeysToCreate(execution.getContext());

        setServiceParameters(serviceToProcess, execution.getContext());

        serviceToProcess = StepsUtil.getServiceToProcess(execution.getContext());

        List<ServiceAction> actions = dermineActionsAndHandleExceptions(controllerClient, spaceId, serviceToProcess, existingService,
                                                                        serviceKeys, execution);

        StepsUtil.setServiceActionsToExecute(actions, execution.getContext());
        StepsUtil.isServiceUpdated(false, execution.getContext());
        StepsUtil.setServiceToProcessName(serviceToProcess.getName(), execution.getContext());
        return StepPhase.DONE;
    }

    @Override
    protected String getStepErrorMessage(DelegateExecution context) {
        return MessageFormat.format(Messages.ERROR_DETERMINING_ACTIONS_TO_EXECUTE_ON_SERVICE, StepsUtil.getServiceToProcess(context)
                                                                                                       .getName());
    }

    private List<ServiceAction> dermineActionsAndHandleExceptions(CloudControllerClient client, String spaceId,
                                                                  CloudServiceExtended service, CloudService existingService,
                                                                  Map<String, List<CloudServiceKey>> serviceKeys,
                                                                  ExecutionWrapper execution)
        throws FileStorageException {
        try {
            return determineActions(client, spaceId, service, existingService, serviceKeys, execution);
        } catch (CloudOperationException e) {
            String determineServiceActionsFailedMessage = MessageFormat.format(Messages.ERROR_DETERMINING_ACTIONS_TO_EXECUTE_ON_SERVICE,
                                                                               service.getName(), e.getStatusText());
            throw new CloudOperationException(e.getStatusCode(), determineServiceActionsFailedMessage, e.getDescription(), e);
        }
    }

    private void setServiceParameters(CloudServiceExtended service, DelegateExecution delegateExecution) throws FileStorageException {
        service = prepareServiceParameters(delegateExecution, service);
        StepsUtil.setServiceToProcess(service, delegateExecution);
    }

    private List<ServiceAction> determineActions(CloudControllerClient client, String spaceId, CloudServiceExtended service,
                                                 CloudService existingService, Map<String, List<CloudServiceKey>> serviceKeys,
                                                 ExecutionWrapper execution)
        throws FileStorageException {
        List<ServiceAction> actions = new ArrayList<>();

        List<CloudServiceKey> keys = serviceKeys.get(service.getResourceName());
        if (shouldUpdateKeys(service, keys)) {
            getStepLogger().debug("Service keys should be updated");
            actions.add(ServiceAction.UPDATE_KEYS);
        }

        if (existingService == null) {
            getStepLogger().debug("Service should be created");
            getStepLogger().debug("New service: " + secureSerializer.toJson(service));
            actions.add(ServiceAction.CREATE);
            StepsUtil.setServicesToCreate(execution.getContext(), Arrays.asList(service));
            return actions;
        }

        Map<String, Object> serviceInstanceEntity = serviceInstanceGetter.getServiceInstanceEntity(client, service.getName(), spaceId);
        // Check if the existing service should be updated or not
        if (shouldRecreate(service, existingService, serviceInstanceEntity, execution)) {
            getStepLogger().debug("Service should be recreated");
            getStepLogger().debug("New service: " + secureSerializer.toJson(service));
            getStepLogger().debug("Existing service: " + secureSerializer.toJson(existingService));
            StepsUtil.setServicesToDelete(execution.getContext(), Arrays.asList(service.getName()));
            actions.add(ServiceAction.RECREATE);
            return actions;
        }

        if (shouldUpdatePlan(service, existingService)) {
            getStepLogger().debug("Service plan should be updated");
            getStepLogger().debug(MessageFormat.format("New service plan: {0}", service.getPlan()));
            getStepLogger().debug(MessageFormat.format("Existing service plan: {0}", existingService.getPlan()));
            actions.add(ServiceAction.UPDATE_PLAN);
        }

        List<String> existingServiceTags = getServiceTags(client, spaceId, existingService);
        if (shouldUpdateTags(service, existingServiceTags)) {
            getStepLogger().debug("Service tags should be updated");
            getStepLogger().debug("New service tags: " + JsonUtil.toJson(service.getTags()));
            getStepLogger().debug("Existing service tags: " + JsonUtil.toJson(existingServiceTags));
            actions.add(ServiceAction.UPDATE_TAGS);
        }

        if (shouldUpdateCredentials(service, existingService, client)) {
            getStepLogger().debug("Service parameters should be updated");
            getStepLogger().debug("New parameters: " + secureSerializer.toJson(service.getCredentials()));
            actions.add(ServiceAction.UPDATE_CREDENTIALS);
        }

        return actions;
    }

    private CloudServiceExtended prepareServiceParameters(DelegateExecution context, CloudServiceExtended service)
        throws FileStorageException {
        MtaArchiveElements mtaArchiveElements = StepsUtil.getMtaArchiveElements(context);
        String fileName = mtaArchiveElements.getResourceFileName(service.getResourceName());
        if (fileName != null) {
            getStepLogger().info(Messages.SETTING_SERVICE_PARAMETERS, service.getName(), fileName);
            String appArchiveId = StepsUtil.getRequiredString(context, Constants.PARAM_APP_ARCHIVE_ID);
            return setServiceParameters(context, service, appArchiveId, fileName);
        }
        return service;
    }

    private CloudServiceExtended setServiceParameters(DelegateExecution context, CloudServiceExtended service, final String appArchiveId,
                                                      final String fileName)
        throws FileStorageException {
        AtomicReference<CloudServiceExtended> serviceReference = new AtomicReference<>();
        FileContentProcessor parametersFileProcessor = appArchiveStream -> {
            try (InputStream is = ArchiveHandler.getInputStream(appArchiveStream, fileName, configuration.getMaxManifestSize())) {
                serviceReference.set(mergeCredentials(service, is));
            } catch (IOException e) {
                throw new SLException(e, Messages.ERROR_RETRIEVING_MTA_RESOURCE_CONTENT, fileName);
            }
        };
        fileService.processFileContent(new DefaultFileDownloadProcessor(StepsUtil.getSpaceId(context),
                                                                        appArchiveId,
                                                                        parametersFileProcessor));
        return serviceReference.get();
    }

    private CloudServiceExtended mergeCredentials(CloudServiceExtended service, InputStream credentialsJson) {
        Map<String, Object> existingCredentials = service.getCredentials();
        Map<String, Object> credentials = JsonUtil.convertJsonToMap(credentialsJson);
        if (existingCredentials == null) {
            existingCredentials = Collections.emptyMap();
        }
        Map<String, Object> result = PropertiesUtil.mergeExtensionProperties(credentials, existingCredentials);
        return ImmutableCloudServiceExtended.copyOf(service)
                                            .withCredentials(result);
    }

    private List<String> getServiceTags(CloudControllerClient client, String spaceId, CloudService service) {
        if (service instanceof CloudServiceExtended) {
            CloudServiceExtended serviceExtended = (CloudServiceExtended) service;
            return serviceExtended.getTags();
        }
        Map<String, Object> serviceInstance = serviceInstanceGetter.getServiceInstanceEntity(client, service.getName(), spaceId);
        return CommonUtil.cast(serviceInstance.get("tags"));
    }

    private boolean shouldUpdateKeys(CloudServiceExtended service, List<CloudServiceKey> serviceKeys) {
        return !(service.isUserProvided() || CollectionUtils.isEmpty(serviceKeys));
    }

    private boolean shouldUpdatePlan(CloudServiceExtended service, CloudService existingService) {
        return !Objects.equals(service.getPlan(), existingService.getPlan());
    }

    private boolean shouldRecreate(CloudServiceExtended service, CloudService existingService, Map<String, Object> serviceInstanceEntity,
                                   ExecutionWrapper execution) {
        if (serviceInstanceEntity == null) {
            return false;
        }
        ServiceOperation lastOperation = getLastOperation(serviceInstanceEntity);
        if (hasServiceFailedState(lastOperation, ServiceOperationType.CREATE)) {
            return true;
        }
        boolean serviceNeedsRecreation = serviceHasDifferentTypeOrLabel(service, existingService)
            || hasServiceFailedState(lastOperation, ServiceOperationType.DELETE);
        if (!StepsUtil.shouldDeleteServices(execution.getContext()) && serviceNeedsRecreation) {
            getStepLogger().debug("Service should be recreated, but delete-services was not enabled.");
            throw new SLException(Messages.ERROR_SERVICE_NEEDS_TO_BE_RECREATED_BUT_FLAG_NOT_SET,
                                  service.getResourceName(),
                                  buildServiceType(service),
                                  existingService.getName(),
                                  buildServiceType(existingService));
        }
        return serviceNeedsRecreation;
    }

    private boolean hasServiceFailedState(ServiceOperation lastOperation, ServiceOperationType serviceOperationType) {
        return lastOperation != null && lastOperation.getType() == serviceOperationType
            && lastOperation.getState() == ServiceOperationState.FAILED;
    }

    private boolean serviceHasDifferentTypeOrLabel(CloudServiceExtended service, CloudService existingService) {
        boolean haveDifferentTypes = service.isUserProvided() ^ existingService.isUserProvided();
        if (existingService.isUserProvided()) {
            return haveDifferentTypes;
        }
        boolean haveDifferentLabels = !Objects.equals(service.getLabel(), existingService.getLabel());
        return haveDifferentTypes || haveDifferentLabels;
    }

    private String buildServiceType(CloudService service) {
        if (service.isUserProvided()) {
            return ResourceType.USER_PROVIDED_SERVICE.toString();
        }
        String label = CommonUtil.isNullOrEmpty(service.getLabel()) ? "unknown label" : service.getLabel();
        String plan = CommonUtil.isNullOrEmpty(service.getPlan()) ? "unknown plan" : service.getPlan();
        return label + "/" + plan;
    }

    private boolean shouldUpdateTags(CloudServiceExtended service, List<String> existingServiceTags) {
        if (service.isUserProvided()) {
            return false;
        }
        existingServiceTags = ObjectUtils.defaultIfNull(existingServiceTags, Collections.<String> emptyList());
        return !existingServiceTags.equals(service.getTags());
    }

    private boolean shouldUpdateCredentials(CloudServiceExtended service, CloudService existingService, CloudControllerClient client) {
        try {
            Map<String, Object> serviceParameters = client.getServiceParameters(existingService.getMetadata()
                                                                                               .getGuid());
            getStepLogger().debug("Existing service parameters: " + secureSerializer.toJson(serviceParameters));
            return !Objects.equals(service.getCredentials(), serviceParameters);
        } catch (CloudOperationException e) {
            if (HttpStatus.NOT_IMPLEMENTED == e.getStatusCode() || HttpStatus.BAD_REQUEST == e.getStatusCode()) {
                getStepLogger().warnWithoutProgressMessage(e, Messages.CANNOT_RETRIEVE_SERVICE_PARAMETERS, service.getName());
                // TODO: Optimization (Hack) that should be deprecated at some point. So here is a todo for that.
                return !MapUtils.isEmpty(service.getCredentials());
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private ServiceOperation getLastOperation(Map<String, Object> cloudServiceInstance) {
        Map<String, Object> lastOperationAsMap = (Map<String, Object>) cloudServiceInstance.get(ServiceOperation.LAST_SERVICE_OPERATION);
        if (lastOperationAsMap == null) {
            return null;
        }
        return parseServiceOperationFromMap(lastOperationAsMap);
    }

    private ServiceOperation parseServiceOperationFromMap(Map<String, Object> serviceOperation) {
        if (serviceOperation.get(ServiceOperation.SERVICE_OPERATION_TYPE) == null
            || serviceOperation.get(ServiceOperation.SERVICE_OPERATION_STATE) == null) {
            return null;
        }
        return ServiceOperation.fromMap(serviceOperation);
    }
}

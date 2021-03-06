package io.cattle.platform.loop;

import io.cattle.platform.core.addon.PortInstance;
import io.cattle.platform.core.addon.metadata.HostInfo;
import io.cattle.platform.core.addon.metadata.InstanceInfo;
import io.cattle.platform.core.addon.metadata.ServiceInfo;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.dao.ClusterDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.engine.manager.LoopFactory;
import io.cattle.platform.engine.manager.LoopManager;
import io.cattle.platform.engine.model.Loop;
import io.cattle.platform.metadata.Metadata;
import io.cattle.platform.metadata.MetadataManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.*;

public class EndpointUpdateLoop implements Loop {

    long accountId;
    MetadataManager metadataManager;
    ObjectManager objectManager;
    LoopManager loopManager;
    ClusterDao clusterDao;

    public EndpointUpdateLoop(long accountId, MetadataManager metadataManager, ObjectManager objectManager, ClusterDao clusterDao, LoopManager loopManager) {
        this.accountId = accountId;
        this.metadataManager = metadataManager;
        this.objectManager = objectManager;
        this.clusterDao = clusterDao;
        this.loopManager = loopManager;
    }

    @Override
    public Result run(List<Object> input) {
        Metadata metadata = metadataManager.getMetadataForAccount(accountId);
        Map<Long, String> agentIps = new HashMap<>();
        Map<Long, ServiceInfo> services = metadata.getServices().stream().collect(toMap(ServiceInfo::getId, (x) -> x));
        Map<Long, Set<PortInstance>> servicePorts = new HashMap<>();
        Metadata clusterMetadata = metadataManager.getMetadataForCluster(metadata.getClusterId());
        for (HostInfo hostInfo : clusterMetadata.getHosts()) {
            if (StringUtils.isNotBlank(hostInfo.getAgentIp())) {
                agentIps.put(hostInfo.getId(), hostInfo.getAgentIp());
            }
        }

        boolean updated = false;
        for (InstanceInfo instanceInfo : metadata.getInstances()) {
            ServiceInfo serviceInfo = services.get(instanceInfo.getServiceId());
            Set<PortInstance> ports = getPortInstances(agentIps, servicePorts, instanceInfo, serviceInfo);

            if (!instanceInfo.getPorts().equals(ports)) {
                updated = true;
                metadata.modify(Instance.class, instanceInfo.getId(), (instance) -> {
                    return objectManager.setFields(instance, InstanceConstants.FIELD_PORT_BINDINGS, ports);
                });
            }
        }

        for (ServiceInfo serviceInfo : metadata.getServices()) {
            Set<PortInstance> ports = servicePorts.get(serviceInfo.getId());
            if (ports == null && serviceInfo.getPorts().size() == 0) {
                continue;
            }

            if (!serviceInfo.getPorts().equals(ports)) {
                updated = true;
                metadata.modify(Service.class, serviceInfo.getId(), (service) -> {
                    return objectManager.setFields(service, ServiceConstants.FIELD_PUBLIC_ENDPOINTS, ports);
                });
            }
        }

        if (updated) {
            loopManager.kick(LoopFactory.HOST_ENDPOINT_UPDATE, Account.class,
                    clusterDao.getOwnerAcccountIdForCluster(metadata.getClusterId()), null);
        }

        return Result.DONE;
    }

    private Set<PortInstance> getPortInstances(Map<Long, String> agentIps, Map<Long, Set<PortInstance>> servicePorts, InstanceInfo instanceInfo, ServiceInfo serviceInfo) {
        Set<PortInstance> ports = new HashSet<>();

        if (!InstanceConstants.STATE_RUNNING.equals(instanceInfo.getState())) {
            return ports;
        }

        for (String portSpecString : instanceInfo.getPortSpecs()) {
            try {
                PortInstance port = new PortInstance(new PortSpec(portSpecString), instanceInfo);
                port.setAgentIpAddress(agentIps.get(instanceInfo.getHostId()));
                port.setFqdn(serviceInfo == null ? null : serviceInfo.getFqdn());
                port.setServiceId(instanceInfo.getServiceId());

                ports.add(port);

                if (serviceInfo != null) {
                    add(servicePorts, serviceInfo.getId(), port);
                }
            } catch (ClientVisibleException ignored) {
            }
        }
        return ports;
    }

    private void add(Map<Long, Set<PortInstance>> map, Long id, PortInstance port) {
        if (id == null) {
            return;
        }

        Set<PortInstance> ports = map.get(id);
        if (ports == null) {
            ports = new HashSet<>();
            map.put(id, ports);
        }

        ports.add(port);
    }

}

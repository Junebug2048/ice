package com.ice.client.trans;

import com.alibaba.fastjson.JSON;
import com.ice.client.IceClient;
import com.ice.client.config.IceClientProperties;
import com.ice.client.change.IceUpdate;
import com.ice.client.utils.AddressUtils;
import com.ice.common.dto.IceTransferDto;
import com.ice.common.enums.NodeTypeEnum;
import com.ice.common.model.IceClientConf;
import com.ice.common.model.IceClientNode;
import com.ice.core.base.BaseNode;
import com.ice.core.base.BaseRelation;
import com.ice.core.cache.IceConfCache;
import com.ice.core.cache.IceHandlerCache;
import com.ice.core.context.IceContext;
import com.ice.core.context.IcePack;
import com.ice.core.handler.IceHandler;
import com.ice.core.leaf.base.BaseLeafFlow;
import com.ice.core.leaf.base.BaseLeafNone;
import com.ice.core.leaf.base.BaseLeafResult;
import com.ice.core.relation.*;
import com.ice.core.utils.IceLinkedList;
import com.ice.rmi.common.client.IceRmiClientService;
import com.ice.rmi.common.server.IceRmiServerService;
import javafx.util.Pair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import javax.rmi.PortableRemoteObject;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

@Slf4j
@Service
public class IceRmiClientServiceImpl implements IceRmiClientService, InitializingBean, DisposableBean {

    @Resource
    private IceRmiClientService remoteClientService;

    @Resource
    private IceClientProperties properties;

    private static Registry registry;

    @Resource
    private Registry iceServerRegistry;

    private static volatile boolean waitInit = true;

    private static volatile long initVersion;

    private List<IceTransferDto> waitMessageList = new ArrayList<>();

    public static void initEnd(long version) {
        waitInit = false;
        initVersion = version;
    }

    @Override
    public Set<Long> getAllConfId(Long iceId) throws RemoteException {
        IceHandler handler = IceHandlerCache.getHandlerById(iceId);
        if (handler != null) {
            BaseNode root = handler.getRoot();
            if (root != null) {
                Set<Long> allIdSet = new HashSet<>();
                findAllConfIds(root, allIdSet);
                return allIdSet;
            }
        }
        return null;
    }

    @Override
    public Pair<Integer, String> confClazzCheck(String clazz, byte type) throws RemoteException {
        try {
            Class<?> clientClazz = Class.forName(clazz);
            NodeTypeEnum typeEnum = NodeTypeEnum.getEnum(type);
            boolean res = false;
            switch (typeEnum) {
                case ALL:
                    res = All.class.isAssignableFrom(clientClazz);
                    break;
                case AND:
                    res = And.class.isAssignableFrom(clientClazz);
                    break;
                case NONE:
                    res = None.class.isAssignableFrom(clientClazz);
                    break;
                case TRUE:
                    res = True.class.isAssignableFrom(clientClazz);
                    break;
                case ANY:
                    res = Any.class.isAssignableFrom(clientClazz);
                    break;
                case LEAF_FLOW:
                    res = BaseLeafFlow.class.isAssignableFrom(clientClazz);
                    break;
                case LEAF_NONE:
                    res = BaseLeafNone.class.isAssignableFrom(clientClazz);
                    break;
                case LEAF_RESULT:
                    res = BaseLeafResult.class.isAssignableFrom(clientClazz);
                    break;
            }
            if (res) {
                return new Pair<>(1, null);
            } else {
                return new Pair<>(0, "type not match in " + AddressUtils.getAddress() + " input(" + clazz + "|" + type + ")");
            }
        } catch (ClassNotFoundException e) {
            return new Pair<>(0, "class not found in " + AddressUtils.getAddress() + " input(" + clazz + "|" + type + ")");
        } catch (Exception e) {
            return new Pair<>(0, AddressUtils.getAddress());
        }
    }

    @Override
    public List<String> update(IceTransferDto dto) throws RemoteException {
        try {
            if (waitInit) {
                log.info("wait init message:{}", JSON.toJSONString(dto));
                waitMessageList.add(dto);
                return null;
            }
            if (!CollectionUtils.isEmpty(waitMessageList)) {
                for (IceTransferDto transferDto : waitMessageList) {
                    handleBeforeInitMessage(transferDto);
                }
                waitMessageList = null;
            }
            handleMessage(dto);
        } catch (Exception e) {
            log.error("ice listener update error message:{} e:", JSON.toJSONString(dto), e);
        }
        return Collections.emptyList();
    }

    private void handleBeforeInitMessage(IceTransferDto dto) {
        if (dto.getVersion() > initVersion) {
            log.info("ice listener update wait msg iceStart iceInfo:{}", dto);
            IceUpdate.update(dto);
            log.info("ice listener update wait msg iceEnd success");
            return;
        }
        log.info("ice listener msg version low then init version:{}, msg:{}", initVersion, JSON.toJSONString(dto));
    }

    private void handleMessage(IceTransferDto dto) {
        log.info("ice listener update msg iceStart dto:{}", JSON.toJSONString(dto));
        IceUpdate.update(dto);
        log.info("ice listener update msg iceEnd success");
    }

    @Override
    public Map<String, Object> getShowConf(Long iceId) throws RemoteException {
        Map<String, Object> resMap = new HashMap<>();
        resMap.put("ip", AddressUtils.getAddress());
        resMap.put("iceId", iceId);
        resMap.put("app", properties.getApp());
        if (iceId <= 0) {
            resMap.put("handlerMap", IceHandlerCache.getIdHandlerMap());
            resMap.put("confMap", IceConfCache.getConfMap());
        } else {
            IceHandler handler = IceHandlerCache.getHandlerById(iceId);
            if (handler != null) {
                Map<String, Object> handlerMap = new HashMap<>();
                handlerMap.put("iceId", handler.findIceId());
                handlerMap.put("scenes", handler.getScenes());
                handlerMap.put("debug", handler.getDebug());
                if (handler.getStart() != 0) {
                    handlerMap.put("start", handler.getStart());
                }
                if (handler.getEnd() != 0) {
                    handlerMap.put("end", handler.getEnd());
                }
                handlerMap.put("timeType", handler.getTimeTypeEnum().getType());
                BaseNode root = handler.getRoot();
                if (root != null) {
                    handlerMap.put("root", assembleNode(root));
                }
                resMap.put("handler", handlerMap);
            }
        }
        return resMap;
    }

    @Override
    public IceClientConf getConf(Long confId) throws RemoteException {
        IceClientConf clientConf = new IceClientConf();
        clientConf.setIp(AddressUtils.getAddress());
        clientConf.setApp(properties.getApp());
        clientConf.setConfId(confId);
        BaseNode node = IceConfCache.getConfById(confId);
        if (node != null) {
            clientConf.setNode(assembleNode1(node));
        }
        return clientConf;
    }

    private IceClientNode assembleNode1(BaseNode node) {
        if (node == null) {
            return null;
        }
        IceClientNode clientNode = new IceClientNode();
        if (node instanceof BaseRelation) {
            BaseRelation relation = (BaseRelation) node;
            IceLinkedList<BaseNode> children = relation.getChildren();
            if (children != null && !children.isEmpty()) {
                List<IceClientNode> showChildren = new ArrayList<>(children.getSize());
                for (IceLinkedList.Node<BaseNode> listNode = children.getFirst();
                     listNode != null; listNode = listNode.next) {
                    BaseNode child = listNode.item;
                    IceClientNode childMap = assembleNode1(child);
                    if (childMap != null) {
                        showChildren.add(childMap);
                    }
                }
                clientNode.setChildren(showChildren);
            }

        }
        BaseNode forward = node.getIceForward();
        if (forward != null) {
            IceClientNode forwardNode = assembleNode1(forward);
            if (forwardNode != null) {
                clientNode.setForward(forwardNode);
            }
        }
        clientNode.setId(node.getIceNodeId() + "");
        clientNode.setTimeType(node.getIceTimeTypeEnum().getType());
        clientNode.setStart(node.getIceStart() == 0 ? null : node.getIceStart());
        clientNode.setEnd(node.getIceEnd() == 0 ? null : node.getIceEnd());
        clientNode.setDebug(node.isIceNodeDebug() ? null : node.isIceNodeDebug());
        clientNode.setInverse(node.isIceInverse() ? node.isIceInverse() : null);
        return clientNode;
    }

    @Override
    public List<IceContext> mock(IcePack pack) throws RemoteException {
        return IceClient.processCxt(pack);
    }

    @Override
    public void ping() throws RemoteException {
        log.info("got ping from server");
    }

    private void findAllConfIds(BaseNode node, Set<Long> ids) {
        Long nodeId = node.getIceNodeId();
        ids.add(nodeId);
        BaseNode forward = node.getIceForward();
        if (forward != null) {
            findAllConfIds(forward, ids);
        }
        if (node instanceof BaseRelation) {
            IceLinkedList<BaseNode> children = ((BaseRelation) node).getChildren();
            if (children == null || children.isEmpty()) {
                return;
            }
            for (IceLinkedList.Node<BaseNode> listNode = children.getFirst();
                 listNode != null; listNode = listNode.next) {
                BaseNode child = listNode.item;
                findAllConfIds(child, ids);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map assembleNode(BaseNode node) {
        if (node == null) {
            return null;
        }
        Map map = new HashMap<>();
        if (node instanceof BaseRelation) {
            BaseRelation relation = (BaseRelation) node;
            IceLinkedList<BaseNode> children = relation.getChildren();
            if (children != null && !children.isEmpty()) {
                List<Map> showChildren = new ArrayList<>(children.getSize());
                for (IceLinkedList.Node<BaseNode> listNode = children.getFirst();
                     listNode != null; listNode = listNode.next) {
                    BaseNode child = listNode.item;
                    Map childMap = assembleNode(child);
                    if (childMap != null) {
                        showChildren.add(childMap);
                    }
                }
                map.put("children", showChildren);
            }
        }
        BaseNode forward = node.getIceForward();
        if (forward != null) {
            Map forwardMap = assembleNode(forward);
            if (forwardMap != null) {
                map.put("forward", forwardMap);
            }
        }
        map.put("nodeId", node.getIceNodeId());
        map.put("timeType", node.getIceTimeTypeEnum().getType());
        if (node.getIceStart() != 0) {
            map.put("start", node.getIceStart());
        }
        if (node.getIceEnd() != 0) {
            map.put("end", node.getIceEnd());
        }
        map.put("debug", node.isIceNodeDebug());
        map.put("inverse", node.isIceInverse());
        return map;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("create ice rmi client service...");
        IceRmiClientService clientService = (IceRmiClientService) UnicastRemoteObject.exportObject(remoteClientService, 0);
        registry = LocateRegistry.createRegistry(properties.getRmi().getPort());
        registry.rebind("IceRemoteClientService", clientService);
        log.info("create ice rmi client service...success");
    }

    @Override
    public void destroy() throws Exception {
        if (registry != null) {
            registry.unbind("IceRemoteClientService");
            UnicastRemoteObject.unexportObject(remoteClientService, true);
            PortableRemoteObject.unexportObject(registry);
            IceRmiServerService serverService = (IceRmiServerService) iceServerRegistry.lookup("IceRemoteServerService");
            serverService.unRegister(properties.getApp(), AddressUtils.getHost(), properties.getRmi().getPort());
        }
    }
}

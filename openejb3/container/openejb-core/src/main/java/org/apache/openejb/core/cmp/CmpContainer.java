/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.core.cmp;

import org.apache.openejb.RpcContainer;
import org.apache.openejb.DeploymentInfo;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.Container;
import org.apache.openejb.ApplicationException;
import org.apache.openejb.InvalidateReferenceException;
import org.apache.openejb.ProxyInfo;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.Enumerator;
import org.apache.openejb.alt.containers.castor_cmp11.KeyGenerator;
import org.apache.openejb.core.transaction.TransactionContainer;
import org.apache.openejb.core.transaction.TransactionPolicy;
import org.apache.openejb.core.transaction.TransactionContext;
import org.apache.openejb.core.CoreDeploymentInfo;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.core.Operations;
import org.apache.openejb.core.entity.EntityContext;

import javax.ejb.EntityBean;
import javax.ejb.RemoveException;
import javax.ejb.EJBException;
import javax.ejb.EJBHome;
import javax.ejb.EJBLocalHome;
import javax.ejb.EJBObject;
import javax.ejb.EJBLocalObject;
import javax.ejb.CreateException;
import javax.ejb.ObjectNotFoundException;
import javax.transaction.TransactionManager;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;

/**
 * @org.apache.xbean.XBean element="cmpContainer"
 */
public class CmpContainer implements RpcContainer, TransactionContainer {
    protected final Object containerID;
    protected final TransactionManager transactionManager;
    protected final SecurityService securityService;
    protected final Map<Object, DeploymentInfo> deploymentsById = new HashMap<Object, DeploymentInfo>();
    protected final Map<Class, DeploymentInfo> deploymentsByClass = new HashMap<Class, DeploymentInfo>();
    protected final CmpEngine cmpEngine;

    public CmpContainer(Object id, TransactionManager transactionManager, SecurityService securityService, HashMap<Object, DeploymentInfo> registry, String cmpEngineFactory, String engine, String connectorName) throws OpenEJBException {
        this.transactionManager = transactionManager;
        this.securityService = securityService;
        this.containerID = id;
        this.deploymentsById.putAll(registry);

        /*
        * This block of code is necessary to avoid a chicken and egg problem.
        * The DeploymentInfo objects must have a reference to their container
        * during this assembly process, but the container is created after the
        * DeploymentInfo necessitating this loop to assign all deployment info
        * object's their containers.
        *
        * In addition the loop is leveraged for other oprations like creating
        * the method ready pool and the keyGenerator pool.
        */
        DeploymentInfo[] deploys = this.deployments();

        for (DeploymentInfo deploymentInfo : deploys) {
            CoreDeploymentInfo di = (CoreDeploymentInfo) deploymentInfo;
            di.setContainer(this);
            deploymentsByClass.put(di.getBeanClass(), di);
        }

        CmpEngineFactory factory = null;
        try {
            Class<?> cmpEngineFactoryClass = getClass().getClassLoader().loadClass(cmpEngineFactory);
            factory = (CmpEngineFactory) cmpEngineFactoryClass.newInstance();
        } catch (Exception e) {
            throw new OpenEJBException("Unable to create cmp engine factory " + cmpEngineFactory, e);
        }
        factory.setTransactionManager(transactionManager);
        factory.setDeploymentInfos(deploys);
        factory.setConnectorName(connectorName);
        factory.setCmpCallback(new ContainerCmpCallback());
        factory.setEngine(engine);
        cmpEngine = factory.create();
    }

    public CmpEngine getCmpEngine() {
        return cmpEngine;
    }

    public DeploymentInfo[] deployments() {
        return deploymentsById.values().toArray(new DeploymentInfo[deploymentsById.size()]);
    }

    public DeploymentInfo getDeploymentInfo(Object deploymentID) {
        return deploymentsById.get(deploymentID);
    }

    public DeploymentInfo getDeploymentInfoByClass(Class beanType) {
        return deploymentsByClass.get(beanType);
    }

    public int getContainerType() {
        return Container.ENTITY;
    }

    public Object getContainerID() {
        return containerID;
    }

    public void deploy(Object deploymentID, DeploymentInfo deploymentInfo) throws OpenEJBException {
        if (true) throw new UnsupportedClassVersionError("DISABLED");
        deploymentsById.put(deploymentID, deploymentInfo);
        deploymentsByClass.put(deploymentInfo.getBeanClass(), deploymentInfo);
        CoreDeploymentInfo di = (CoreDeploymentInfo) deploymentInfo;
        di.setContainer(this);
    }

    public Object invoke(Object deployID, Method callMethod, Object[] args, Object primKey, Object securityIdentity) throws OpenEJBException {
        CoreDeploymentInfo deployInfo = (CoreDeploymentInfo) this.getDeploymentInfo(deployID);
        ThreadContext callContext = new ThreadContext();
        callContext.set(deployInfo, primKey, securityIdentity);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {

            boolean authorized = securityService.isCallerAuthorized(securityIdentity, deployInfo.getAuthorizedRoles(callMethod));
            if (!authorized) {
                throw new ApplicationException(new RemoteException("Unauthorized Access by Principal Denied"));
            }

            Class declaringClass = callMethod.getDeclaringClass();
            String methodName = callMethod.getName();

            if (EJBHome.class.isAssignableFrom(declaringClass) || EJBLocalHome.class.isAssignableFrom(declaringClass)) {
                if (declaringClass != EJBHome.class && declaringClass != EJBLocalHome.class) {
                    if (methodName.equals("create")) {
                        return createEJBObject(callMethod, args, callContext);
                    } else if (methodName.equals("findByPrimaryKey")) {
                        return findByPrimaryKey(callMethod, args, callContext);
                    } else if (methodName.startsWith("find")) {
                        return findEJBObject(callMethod, args, callContext);
                    } else {
                        throw new InvalidateReferenceException(new RemoteException("Invalid method " + methodName + " only find<METHOD>( ) and create( ) method are allowed in EJB 1.1 container-managed persistence"));
                    }
                } else if (methodName.equals("remove")) {
                    removeEJBObject(callMethod, callContext);
                    return null;
                }
            } else if ((EJBObject.class == declaringClass || EJBLocalObject.class == declaringClass) && methodName.equals("remove")) {
                removeEJBObject(callMethod, callContext);
                return null;
            }

            // business method
            callContext.setCurrentOperation(Operations.OP_BUSINESS);
            Method runMethod = deployInfo.getMatchingBeanMethod(callMethod);

            Object retValue = businessMethod(callMethod, runMethod, args, callContext);

            return retValue;
        } finally {
            /*
                The thread context must be stripped from the thread before returning or throwing an exception
                so that an object outside the container does not have access to a
                bean's JNDI ENC.  In addition, its important for the
                org.apache.openejb.core.ivm.java.javaURLContextFactory, which determines the context
                of a JNDI lookup based on the presence of a ThreadContext object.  If no ThreadContext
                object is available, then the request is assumed to be made from outside the container
                system and is given the global OpenEJB JNDI name space instead.  If there is a thread context,
                then the request is assumed to be made from within the container system and so the
                javaContextFactory must return the JNDI ENC of the current enterprise bean which it
                obtains from the DeploymentInfo object associated with the current thread context.
            */
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    public void discardInstance(Object bean, ThreadContext threadContext) {
    }

    private EntityBean createNewInstance(ThreadContext callContext) {
        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();
        try {
            EntityBean bean = (EntityBean) deploymentInfo.getBeanClass().newInstance();
            return bean;
        } catch (Exception e) {
            throw new EJBException("Unable to create new entity bean instance");
        }
    }

    private ThreadContext createThreadContext(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        CoreDeploymentInfo deployInfo = (CoreDeploymentInfo) getDeploymentInfoByClass(entityBean.getClass());
        KeyGenerator keyGenerator = deployInfo.getKeyGenerator();
        Object primaryKey = keyGenerator.getPrimaryKey(entityBean);

        ThreadContext callContext = new ThreadContext();
        callContext.set(deployInfo, primaryKey, null);
        return callContext;
    }

    private void setEntityContext(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        // activating entity doen't have a primary key
        CoreDeploymentInfo deployInfo = (CoreDeploymentInfo) getDeploymentInfoByClass(entityBean.getClass());

        ThreadContext callContext = new ThreadContext();
        callContext.setDeploymentInfo(deployInfo);
        callContext.setCurrentOperation(Operations.OP_SET_CONTEXT);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.setEntityContext(new EntityContext(transactionManager, securityService));
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private void unsetEntityContext(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        ThreadContext callContext = createThreadContext(entityBean);
        callContext.setCurrentOperation(Operations.OP_UNSET_CONTEXT);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.unsetEntityContext();
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private void ejbLoad(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        ThreadContext callContext = createThreadContext(entityBean);
        callContext.setCurrentOperation(Operations.OP_LOAD);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.ejbLoad();
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private void ejbStore(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        ThreadContext callContext = createThreadContext(entityBean);
        callContext.setCurrentOperation(Operations.OP_STORE);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.ejbStore();
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private void ejbRemove(EntityBean entityBean) throws RemoveException {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        ThreadContext callContext = createThreadContext(entityBean);
        callContext.setCurrentOperation(Operations.OP_REMOVE);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.ejbRemove();
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private void ejbActivate(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        // activating entity doen't have a primary key
        CoreDeploymentInfo deployInfo = (CoreDeploymentInfo) getDeploymentInfoByClass(entityBean.getClass());

        ThreadContext callContext = new ThreadContext();
        callContext.setDeploymentInfo(deployInfo);
        callContext.setCurrentOperation(Operations.OP_ACTIVATE);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.ejbActivate();
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private void ejbPassivate(EntityBean entityBean) {
        if (entityBean == null) throw new NullPointerException("entityBean is null");

        ThreadContext callContext = createThreadContext(entityBean);
        callContext.setCurrentOperation(Operations.OP_PASSIVATE);

        ThreadContext oldCallContext = ThreadContext.getThreadContext();
        ThreadContext.setThreadContext(callContext);
        try {
            entityBean.ejbPassivate();
        } catch (RemoteException e) {
            throw new EJBException(e);
        } finally {
            ThreadContext.setThreadContext(oldCallContext);
        }
    }

    private Object businessMethod(Method callMethod, Method runMethod, Object[] args, ThreadContext callContext) throws OpenEJBException {
        TransactionPolicy txPolicy = callContext.getDeploymentInfo().getTransactionPolicy(callMethod);
        TransactionContext txContext = new TransactionContext(callContext, transactionManager);

        txPolicy.beforeInvoke(null, txContext);

        EntityBean bean = null;
        Object returnValue = null;
        try {
            bean = (EntityBean) cmpEngine.loadBean(callContext, callContext.getPrimaryKey());

            returnValue = runMethod.invoke(bean, args);

        } catch (InvocationTargetException ite) {

            if (ite.getTargetException() instanceof RuntimeException) {
                /* System Exception ****************************/
                txPolicy.handleSystemException(ite.getTargetException(), bean, txContext);

            } else {
                /* Application Exception ***********************/
                txPolicy.handleApplicationException(ite.getTargetException(), txContext);
            }
        } catch (Throwable e) {
            /* System Exception ****************************/
            txPolicy.handleSystemException(e, bean, txContext);
        } finally {
            txPolicy.afterInvoke(bean, txContext);
        }

        return returnValue;
    }

    private ProxyInfo createEJBObject(Method callMethod, Object[] args, ThreadContext callContext) throws OpenEJBException {
        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();

        EntityBean bean = null;
        Object primaryKey = null;

        TransactionPolicy txPolicy = callContext.getDeploymentInfo().getTransactionPolicy(callMethod);
        TransactionContext txContext = new TransactionContext(callContext, transactionManager);

        txPolicy.beforeInvoke(bean, txContext);

        try {

            /*
              Obtain a bean instance from the method ready pool
            */
            bean = createNewInstance(callContext);

            // set the entity context
            setEntityContext(bean);

            /*
               Obtain the proper ejbCreate() method
            */
            Method ejbCreateMethod = deploymentInfo.getMatchingBeanMethod(callMethod);

            /*
              Set the context for allowed operations
            */
            callContext.setCurrentOperation(Operations.OP_CREATE);

            /*
              Invoke the proper ejbCreate() method on the instance
            */
            ejbCreateMethod.invoke(bean, args);

            // create the new bean
            primaryKey = cmpEngine.createBean(bean, callContext);

            /*
              place the primary key into the current ThreadContext so its available for
              the ejbPostCreate()
            */
            callContext.setPrimaryKey(primaryKey);

            /*
              Set the current operation for the allowed operations check
            */
            callContext.setCurrentOperation(Operations.OP_POST_CREATE);

            /*
              Obtain the ejbPostCreate method that matches the ejbCreate method
            */
            Method ejbPostCreateMethod = deploymentInfo.getMatchingPostCreateMethod(ejbCreateMethod);

            /*
              Invoke the ejbPostCreate method on the bean instance
            */
            ejbPostCreateMethod.invoke(bean, args);

            /*
            According to section 9.1.5.1 of the EJB 1.1 specification, the "ejbPostCreate(...)
            method executes in the same transaction context as the previous ejbCreate(...) method."

            The bean is first insterted using db.create( ) and then after ejbPostCreate( ) its
            updated using db.update(). This protocol allows for visablity of the bean after ejbCreate
            within the current trasnaction.
            */

            /*
              Reset the primary key in the ThreadContext to null, its original value
            */
            callContext.setPrimaryKey(null);

        } catch (InvocationTargetException ite) {// handle enterprise bean exceptions
            if (ite.getTargetException() instanceof RuntimeException) {
                /* System Exception ****************************/
                txPolicy.handleSystemException(ite.getTargetException(), bean, txContext);
            } else {
                /* Application Exception ***********************/
                txPolicy.handleApplicationException(ite.getTargetException(), txContext);
            }
        } catch (CreateException e) {
            txPolicy.handleSystemException(e, bean, txContext);
        } catch (Throwable e) {
            txPolicy.handleSystemException(e, bean, txContext);
        } finally {
            txPolicy.afterInvoke(bean, txContext);
        }

        Class callingClass = callMethod.getDeclaringClass();
        Class objectInterface = deploymentInfo.getObjectInterface(callingClass);
        return new ProxyInfo(deploymentInfo, primaryKey, objectInterface, this);
    }

    private Object findByPrimaryKey(Method callMethod, Object[] args, ThreadContext callContext) throws OpenEJBException {
        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();

        // Get the transaction policy assigned to this method
        TransactionPolicy txPolicy = callContext.getDeploymentInfo().getTransactionPolicy(callMethod);
        TransactionContext txContext = new TransactionContext(callContext, transactionManager);

        txPolicy.beforeInvoke(null, txContext);
        try {
            EntityBean bean = (EntityBean) cmpEngine.loadBean(callContext, args[0]);

            // rebuild the primary key
            KeyGenerator kg = deploymentInfo.getKeyGenerator();
            Object primaryKey = kg.getPrimaryKey(bean);

            // Determine the proxy type
            Class<?> callingClass = callMethod.getDeclaringClass();
            Class objectInterface = deploymentInfo.getObjectInterface(callingClass);

            // create a new ProxyInfo based on the deployment info and primary key
            return new ProxyInfo(deploymentInfo, primaryKey, objectInterface, this);
        } catch (Throwable e) {// handle reflection exception
            txPolicy.handleSystemException(e, null, txContext);
        } finally {
            txPolicy.afterInvoke(null, txContext);
        }
        throw new AssertionError("Should not get here");
    }

    private Object findEJBObject(Method callMethod, Object[] args, ThreadContext callContext) throws OpenEJBException {
        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();

        // Obtain the query for the called method
        String queryString = deploymentInfo.getQuery(callMethod);

        // Get the transaction policy assigned to this method
        TransactionPolicy txPolicy = callContext.getDeploymentInfo().getTransactionPolicy(callMethod);
        TransactionContext txContext = new TransactionContext(callContext, transactionManager);

        txPolicy.beforeInvoke(null, txContext);
        try {
            List<Object> results = cmpEngine.queryBeans(callContext, queryString, args);

            KeyGenerator kg = deploymentInfo.getKeyGenerator();

            Class<?> callingClass = callMethod.getDeclaringClass();
            Class objectInterface = deploymentInfo.getObjectInterface(callingClass);

            /*
            The following block of code is responsible for returning ProxyInfo object(s) for each
            matching entity bean found by the query.  If its a multi-value find operation a Vector
            of ProxyInfo objects will be returned. If its a single-value find operation then a
            single ProxyInfo object is returned.
            */
            if (callMethod.getReturnType() == Collection.class || callMethod.getReturnType() == Enumeration.class) {
                Vector<ProxyInfo> proxies = new Vector<ProxyInfo>();
                for (Object value : results) {
                    EntityBean bean = (EntityBean) value;

                    /*
                    The KeyGenerator creates a new primary key and populates its fields with the
                    primary key fields of the bean instance.  Each deployment has its own KeyGenerator.
                    */
                    Object primaryKey = kg.getPrimaryKey(bean);
                    /*   create a new ProxyInfo based on the deployment info and primary key and add it to the vector */
                    proxies.addElement(new ProxyInfo(deploymentInfo, primaryKey, objectInterface, this));
                }
                if (callMethod.getReturnType() == Enumeration.class) {
                    return new Enumerator(proxies);
                } else {
                    return proxies;
                }
            } else {
                if (results.size() != 1) throw new ObjectNotFoundException("A Enteprise bean with deployment_id = " + deploymentInfo.getDeploymentID() + " and primarykey = " + args[0] + " Does not exist");

                // create a new ProxyInfo based on the deployment info and primary key
                EntityBean bean = (EntityBean) results.get(0);
                Object primaryKey = kg.getPrimaryKey(bean);
                return new ProxyInfo(deploymentInfo, primaryKey, objectInterface, this);
            }
        } catch (javax.ejb.FinderException fe) {
            txPolicy.handleApplicationException(fe, txContext);
        } catch (Throwable e) {// handle reflection exception
            txPolicy.handleSystemException(e, null, txContext);
        } finally {
            txPolicy.afterInvoke(null, txContext);
        }
        throw new AssertionError("Should not get here");
    }

    private void removeEJBObject(Method callMethod, ThreadContext callContext) throws OpenEJBException {
        EntityBean bean = null;
        TransactionContext txContext = new TransactionContext(callContext, transactionManager);
        TransactionPolicy txPolicy = callContext.getDeploymentInfo().getTransactionPolicy(callMethod);

        txPolicy.beforeInvoke(bean, txContext);
        try {
            cmpEngine.removeBean(callContext);
        } catch (Throwable e) {// handle reflection exception
            txPolicy.handleSystemException(e, bean, txContext);
        } finally {
            txPolicy.afterInvoke(bean, txContext);
        }
    }

    private class ContainerCmpCallback implements CmpCallback {
        public void setEntityContext(EntityBean entity) {
            CmpContainer.this.setEntityContext(entity);
        }

        public void unsetEntityContext(EntityBean entity) {
            CmpContainer.this.unsetEntityContext(entity);
        }

        public void ejbActivate(EntityBean entity) {
            CmpContainer.this.ejbActivate(entity);
        }

        public void ejbPassivate(EntityBean entity) {
            CmpContainer.this.ejbPassivate(entity);
        }

        public void ejbLoad(EntityBean entity) {
            CmpContainer.this.ejbLoad(entity);
        }

        public void ejbStore(EntityBean entity) {
            CmpContainer.this.ejbStore(entity);
        }

        public void ejbRemove(EntityBean entity) throws RemoveException {
            CmpContainer.this.ejbRemove(entity);
        }
    }
}

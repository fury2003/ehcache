package net.sf.ehcache.distribution.jms;

import net.sf.ehcache.Element;
import net.sf.ehcache.MimeTypeByteArray;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.config.ConfigurationFactory;
import static net.sf.ehcache.distribution.jms.JMSEventMessage.ACTION_PROPERTY;
import static net.sf.ehcache.distribution.jms.JMSEventMessage.CACHE_NAME_PROPERTY;
import static net.sf.ehcache.distribution.jms.JMSEventMessage.KEY_PROPERTY;
import static net.sf.ehcache.distribution.jms.JMSEventMessage.MIME_TYPE_PROPERTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.sun.messaging.ConnectionConfiguration;
import com.sun.messaging.jmq.admin.objstore.ObjStore;
import com.sun.messaging.jmq.admin.objstore.ObjStoreAttrs;
import com.sun.messaging.jmq.admin.objstore.ObjStoreManager;
import com.sun.messaging.jmq.admin.util.JMSObjFactory;
import com.sun.messaging.jmq.jmsclient.runtime.BrokerInstance;
import com.sun.messaging.jmq.jmsclient.runtime.ClientRuntime;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.auth.file.JMQFileUserRepository;
import com.sun.messaging.jmq.jmsserver.auth.usermgr.PasswdDB;
import com.sun.messaging.jmq.jmsserver.auth.usermgr.UserInfo;
import com.sun.messaging.jmq.jmsservice.BrokerEvent;
import com.sun.messaging.jmq.jmsservice.BrokerEventListener;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;

import java.io.File;
import java.io.Serializable;
import java.net.URL;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;

import static net.sf.ehcache.distribution.jms.AbstractJMSReplicationTest.SAMPLE_CACHE_ASYNC;

/**
 * Run the tests using Open MQ
 * The create_administered_objects needs to have been run first
 *
 * @author Greg Luck
 */
public class OpenMqJMSReplicationTest extends AbstractJMSReplicationTest {

    private static final Logger LOG = Logger.getLogger(OpenMqJMSReplicationTest.class.getName());

    @Override
    protected URL getConfiguration() {
        return OpenMqJMSReplicationTest.class.getResource("/distribution/jms/ehcache-distributed-jms-openmq.xml");
    }

    private static BrokerInstance BROKER;
    
    @BeforeClass
    public static void startOpenMQ() throws Exception {
      ClientRuntime runtime = ClientRuntime.getRuntime();
      BROKER = runtime.createBrokerInstance();
      BrokerEventListener listener = new BrokerEventListener() {
        
        public boolean exitRequested(BrokerEvent arg0, Throwable arg1) {
          return true;
        }
        
        public void brokerEvent(BrokerEvent arg0) {
          //no-op
        }
      };


      BROKER.init(BROKER.parseArgs("-imqhome target/openmq/mq".split(" ")), listener);
      BROKER.start();
      
      File passwdFile = JMQFileUserRepository.getPasswordFile(Globals.getConfig(), true);
      PasswdDB.setPasswordFileName(passwdFile.getCanonicalPath());
      PasswdDB pwdDb = new PasswdDB();
      if (pwdDb.getUserInfo("test") == null) {
        pwdDb.addUser("test", "test", UserInfo.ROLE_USER);
      }

      ObjStoreAttrs storeAttributes = new ObjStoreAttrs();
      File jndiPath = new File("/tmp");
      jndiPath.mkdirs();
      storeAttributes.put("java.naming.provider.url", "file://" + jndiPath.getCanonicalPath());
      storeAttributes.put("java.naming.factory.initial", "com.sun.jndi.fscontext.RefFSContextFactory");
      ObjStore objStore = ObjStoreManager.getObjStoreManager().createStore(storeAttributes);
      objStore.open();

      Properties tcfProps = new Properties();
      tcfProps.setProperty("imqReconnect", "true");
      tcfProps.setProperty("imqPingInterval", "5");
      tcfProps.setProperty("imqDefaultUsername", "test");
      tcfProps.setProperty("imqDefaultPassword", "test");
      Object topicConnectionFactory = JMSObjFactory.createTopicConnectionFactory(tcfProps);
      objStore.add("MyConnectionFactory", topicConnectionFactory, true);
      
      Properties qfProps = new Properties();
      qfProps.setProperty("imqReconnect", "true");
      qfProps.setProperty("imqPingInterval", "5");
      qfProps.setProperty("imqDefaultUsername", "test");
      qfProps.setProperty("imqDefaultPassword", "test");
      Object queueConnectionFactory = JMSObjFactory.createQueueConnectionFactory(qfProps);
      objStore.add("queueConnectionFactory", queueConnectionFactory, true);

      Properties topicProps = new Properties();
      topicProps.setProperty("imqDestinationName", "EhcacheTopicDest");
      Object topic = JMSObjFactory.createTopic(topicProps);
      objStore.add("ehcache", topic, true);

      Properties queueProps = new Properties();
      queueProps.setProperty("imqDestinationName", "EhcacheGetQueueDest");
      Object queue = JMSObjFactory.createQueue(queueProps);
      objStore.add("ehcacheGetQueue", queue, true);
    }
    
    @AfterClass
    public static void stopOpenMQ() throws Exception {
      ObjStoreManager.getObjStoreManager().destroyStore("default");
      BROKER.stop();
      BROKER.shutdown();
    }

    @Test
    public void testNonCachePublisherElementMessagePut() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherElementMessagePut", 2);
        try {
            Element payload = new Element("1234", "dog");
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage(payload);
            message.setStringProperty(ACTION_PROPERTY, Action.PUT.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.
            message.setStringProperty(KEY_PROPERTY, "ignore");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsEqual.equalTo(payload));
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsEqual.equalTo(payload));
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherObjectMessagePut() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherObjectMessagePut", 2);
        try {
            String payload = "this is an object";
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage(payload);
            message.setStringProperty(ACTION_PROPERTY, Action.PUT.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.
            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.valueAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsEqual.<Object>equalTo(payload));
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.valueAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsEqual.<Object>equalTo(payload));
        } finally {
            destroyCluster(cluster);
        }
    }


    @Test
    public void testNonCachePublisherByteMessagePut() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherByteMessagePut", 1);
        try {
            byte[] bytes = new byte[]{0x34, (byte) 0xe3, (byte) 0x88};
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            BytesMessage message = publisherSession.createBytesMessage();
            message.writeBytes(bytes);
            message.setStringProperty(ACTION_PROPERTY, Action.PUT.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            message.setStringProperty(MIME_TYPE_PROPERTY, "application/x-greg");
            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.notNullValue());
            MimeTypeByteArray payload = ((MimeTypeByteArray) cluster.get(0).getCache(SAMPLE_CACHE_ASYNC).get("1234").getObjectValue());
            assertEquals("application/x-greg", payload.getMimeType());
            assertEquals(new String(bytes), new String(payload.getValue()));
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherByteMessageNoMimeTypePut() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherByteMessageNoMimeTypePut", 1);
        try {
            byte[] bytes = "these are bytes".getBytes();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            BytesMessage message = publisherSession.createBytesMessage();
            message.writeBytes(bytes);
            message.setStringProperty(ACTION_PROPERTY, Action.PUT.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
    //        message.setStringProperty(MIME_TYPE_PROPERTY, "application/octet-stream");
            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.notNullValue());
            MimeTypeByteArray payload = ((MimeTypeByteArray) cluster.get(0).getCache(SAMPLE_CACHE_ASYNC).get("1234").getObjectValue());
            assertEquals("application/octet-stream", payload.getMimeType());
            assertEquals(new String(bytes), new String(payload.getValue()));
        } finally {
            destroyCluster(cluster);
        }
    }


    @Test
    public void testNonCachePublisherTextMessagePut() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherTextMessagePut", 1);
        try {
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            String value = "<?xml version=\"1.0\"?>\n" +
                    "<oldjoke>\n" +
                    "<burns>Say <quote>goodnight</quote>,\n" +
                    "Gracie.</burns>\n" +
                    "<allen><quote>Goodnight, \n" +
                    "Gracie.</quote></allen>\n" +
                    "<applause/>\n" +
                    "</oldjoke>";

            TextMessage message = publisherSession.createTextMessage(value);
            message.setStringProperty(ACTION_PROPERTY, Action.PUT.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            message.setStringProperty(MIME_TYPE_PROPERTY, "text/x-greg");
            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.notNullValue());
            MimeTypeByteArray payload = ((MimeTypeByteArray) cluster.get(0).getCache(SAMPLE_CACHE_ASYNC).get("1234").getObjectValue());
            assertEquals("text/x-greg", payload.getMimeType());
            assertEquals(value, new String(payload.getValue()));
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherTextMessageNoMimeTypePut() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherTextMessageNoMimeTypePut", 1);
        try {
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            String value = "this is a string";
            TextMessage message = publisherSession.createTextMessage(value);
            message.setStringProperty(ACTION_PROPERTY, Action.PUT.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
    //        message.setStringProperty(MIME_TYPE_PROPERTY, "text/plain");
            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.notNullValue());
            MimeTypeByteArray payload = ((MimeTypeByteArray) cluster.get(0).getCache(SAMPLE_CACHE_ASYNC).get("1234").getObjectValue());
            assertEquals("text/plain", payload.getMimeType());
            assertEquals(value, new String(payload.getValue()));
        } finally {
            destroyCluster(cluster);
        }
    }


    @Test
    public void testNonCachePublisherObjectMessageRemove() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherObjectMessageRemove", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.

            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherBytesMessageRemove() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherBytesMessageRemove", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            BytesMessage message = publisherSession.createBytesMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.

            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherTextMessageRemove() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherTextMessageRemove", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            TextMessage message = publisherSession.createTextMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.

            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }


    /**
     * Use the property key even if an element is sent with remove
     */
    @Test
    public void testNonCachePublisherElementMessageRemove() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherElementMessageRemove", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage(new Element("ignored", "dog"));
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.

            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherObjectMessageRemoveAll() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherObjectMessageRemoveAll", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE_ALL.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);

            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherElementMessageRemoveAll() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherElementMessageRemoveAll", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage(new Element("1", "dog"));
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE_ALL.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.

            message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherTextMessageRemoveAll() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherTextMessageRemoveAll", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            TextMessage message = publisherSession.createTextMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE_ALL.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    @Test
    public void testNonCachePublisherBytesMessageRemoveAll() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherBytesMessageRemoveAll", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            BytesMessage message = publisherSession.createBytesMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE_ALL.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }


    /**
     * Malformed Test - no properties at all set
     */
    @Test
    public void testNonCachePublisherPropertiesNotSet() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherPropertiesNotSet", 1);
        try {
            Element payload = new Element("1234", "dog");
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
            ObjectMessage message = publisherSession.createObjectMessage(payload);
            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.nullValue());
        } finally {
            destroyCluster(cluster);
        }
    }


    /**
     * Malformed test
     * Does not work if do not set key
     */
    @Test
    public void testNonCachePublisherElementMessageRemoveNoKey() throws JMSException, InterruptedException {
        List<CacheManager> cluster = createCluster("testNonCachePublisherElementMessageRemoveNoKey", 2);
        try {
            //make sure there is an element
            testNonCachePublisherElementMessagePut();
            TopicConnection connection = getMQConnection();
            connection.start();

            TopicSession publisherSession = connection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

            ObjectMessage message = publisherSession.createObjectMessage();
            message.setStringProperty(ACTION_PROPERTY, Action.REMOVE.name());
            message.setStringProperty(CACHE_NAME_PROPERTY, SAMPLE_CACHE_ASYNC);
            //don't set. Should work.
            //message.setStringProperty(MIME_TYPE_PROPERTY, null);
            //should work. Key should be ignored when sending an element.

            //won't work because key not set
    //        message.setStringProperty(KEY_PROPERTY, "1234");


            Topic topic = publisherSession.createTopic("EhcacheTopicDest");
            TopicPublisher publisher = publisherSession.createPublisher(topic);
            publisher.send(message);

            connection.stop();

            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(0).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.notNullValue());
            RetryAssert.assertBy(1, TimeUnit.SECONDS, RetryAssert.elementAt(cluster.get(1).getCache(SAMPLE_CACHE_ASYNC), "1234"), IsNull.notNullValue());
        } finally {
            destroyCluster(cluster);
        }
    }

    /**
     * Gets a connection without using JNDI, so that it is fully independent.
     *
     * @throws JMSException
     */
    private TopicConnection getMQConnection() throws JMSException {
        com.sun.messaging.ConnectionFactory factory = new com.sun.messaging.ConnectionFactory();
        factory.setProperty(ConnectionConfiguration.imqAddressList, "localhost:7676");
        factory.setProperty(ConnectionConfiguration.imqReconnectEnabled, "true");
        factory.setProperty(ConnectionConfiguration.imqDefaultUsername, "test");
        factory.setProperty(ConnectionConfiguration.imqDefaultPassword, "test");
        TopicConnection myConnection = factory.createTopicConnection();
        return myConnection;
    }


    //allow tests to run faster
    @Ignore
    @Test
    public void testGetConcurrent() throws Exception {
        List<CacheManager> cluster = createCluster("testGetConcurrent", 2);
        try {
            final long maxTime = 5000;

            Thread.sleep(2000);

            final Ehcache cache1 = cluster.get(0).getCache(SAMPLE_CACHE_NOREP);
            final Ehcache cache2 = cluster.get(1).getCache(SAMPLE_CACHE_NOREP);


            long start = System.currentTimeMillis();
            final List executables = new ArrayList();
            final Random random = new Random();


            //some of the time get data
            for (int i = 0; i < 50; i++) {
                final int i1 = i;
                final TestUtil.Executable executable = new TestUtil.Executable() {
                    public void execute() throws Exception {


                        final Serializable key = "" + i1;
                        final Serializable value = new Date();
                        Element element = new Element(key, i1);

                        //Put
                        cache1.put(element);
                        Thread.sleep(1050);

                        //Should load from cache1
                        for (int i = 0; i < 20; i++) {
                            final TestUtil.StopWatch stopWatch = new TestUtil.StopWatch();
                            long start = stopWatch.getElapsedTime();
                            Element element2 = cache2.getWithLoader(key, null, null);
                            assertEquals(i1, element2.getValue());
                            cache2.remove(key);
                            long end = stopWatch.getElapsedTime();
                            long elapsed = end - start;
                            assertTrue("Get time outside of allowed time: " + elapsed, elapsed < maxTime);
                        }

                    }
                };
                executables.add(executable);
            }


            TestUtil.runThreads(executables);
            long end = System.currentTimeMillis();
            LOG.info("Total time for the test: " + (end - start) + " ms");
        } finally {
            destroyCluster(cluster);
        }
    }


    /**
     * Same as get, but this one tests out a few things that can cause problems with message queues (and have been
     * reproduced with this test - until the code was corrected that is)
     * <p/>
     * 1. Do two loops of 1000 requests. If there is any resource leakage this will fail
     * 2. Find a UID so that the reqestor does not satisfy its own request
     * 3. Pause for 125 seconds between the two runs. Open MQ closes unused destinations after 120 seconds.
     */
    //allow tests to run faster
    @Ignore
    @Test
    public void testGetStability() throws InterruptedException {
        List<CacheManager> cluster = createCluster("testGetStability", 2);
        try {
            Ehcache cache1 = cluster.get(0).getCache(SAMPLE_CACHE_NOREP);
            Ehcache cache2 = cluster.get(1).getCache(SAMPLE_CACHE_NOREP);

            Serializable key = "1";
            Serializable value = new Date();
            Element element = new Element(key, value);

            //Put
            cache1.put(element);
            long version = element.getVersion();
            Thread.sleep(1050);


            //Should not have been replicated to cache2.
            Element element2 = cache2.get(key);
            assertEquals(null, element2);

            //Should load from cache1
            for (int i = 0; i < 1000; i++) {
                element2 = cache2.getWithLoader(key, null, null);
                assertEquals(value, element2.getValue());
                cache2.remove(key);
            }
        } finally {
            destroyCluster(cluster);
        }
    }

    /**
     * Manual test.
     * <p/>
     * Run the test, stop the message queue and then start the message queue. load should throw exceptions but then
     * start loading again shortly after the message queue restarts.
     */
    @Ignore
    @Test
    public void testGetMessageQueueFailure() throws InterruptedException {
        List<CacheManager> cluster = createCluster("testGetMessageQueueFailure", 2);
        try {
            Ehcache cache1 = cluster.get(0).getCache(SAMPLE_CACHE_NOREP);
            Ehcache cache2 = cluster.get(0).getCache(SAMPLE_CACHE_NOREP);

            Serializable key = "1";
            Serializable value = new Date();
            Element element = new Element(key, value);

            //Put
            cache1.put(element);
            long version = element.getVersion();
            Thread.sleep(1050);


            //Should not have been replicated to cache2.
            Element element2 = cache2.get(key);
            assertEquals(null, element2);

            //Should load from cache1
            for (int i = 0; i < 1000; i++) {
                Thread.sleep(2000);
                try {
                    element2 = cache2.getWithLoader(key, null, null);
                } catch (CacheException e) {
                    e.printStackTrace();
                }
                assertEquals(value, element2.getValue());
                cache2.remove(key);
            }
        } finally {
            destroyCluster(cluster);
        }
    }

    /**
     * Uses the JMSCacheLoader.
     * <p/>
     * We do not put an item in cache1, which does not replicate.
     * <p/>
     * We then do a get on cache2, which has a JMSCacheLoader which should ask the cluster for the answer.
     * If a cache does not have an element it should leave the message on the queue for the next node to process.
     */
    @Test
    public void testGetNull() throws InterruptedException {
        List<CacheManager> cluster = createCluster("testGetNull", 2);
        try {
            Ehcache cache1 = cluster.get(0).getCache(SAMPLE_CACHE_NOREP);
            Ehcache cache2 = cluster.get(1).getCache(SAMPLE_CACHE_NOREP);

            Serializable key = "1";

            //Should not have been replicated to cache2.
            Element element2 = cache2.get(key);
            assertEquals(null, element2);

            //Should load from cache1
            for (int i = 0; i < 100; i++) {
                Element element = cache2.getWithLoader(key, null, null);
                assertNull("" + element2, element2);
            }
        } finally {
            destroyCluster(cluster);
        }
    }


//    @Test
//    public void testOneWayReplicateContinuous() throws Exception {
//        for (int i = 0; i < 10; i++) {
//            testOneWayReplicate();
//        }
//    }


    @Test
    public void testOneWayReplicate() throws Exception {
        URL nonListeningConfigurationFile = OpenMqJMSReplicationTest.class.getResource("/distribution/jms/ehcache-distributed-nonlistening-jms-openmq.xml");
        URL listeningConfigurationFile = OpenMqJMSReplicationTest.class.getResource("/distribution/jms/ehcache-distributed-jms-openmq.xml");

        CacheManager managerA = new CacheManager(ConfigurationFactory.parseConfiguration(nonListeningConfigurationFile).name("testOneWayReplicateA"));
        CacheManager managerB = new CacheManager(ConfigurationFactory.parseConfiguration(listeningConfigurationFile).name("testOneWayReplicateB"));
        CacheManager managerC = new CacheManager(ConfigurationFactory.parseConfiguration(nonListeningConfigurationFile).name("testOneWayReplicateC"));
        try {
            Thread.sleep(5000);

            Element element = new Element("1", "value");
            managerA.getCache(SAMPLE_CACHE_ASYNC).put(element);

            RetryAssert.assertBy(4, TimeUnit.SECONDS, RetryAssert.elementAt(managerA.getCache(SAMPLE_CACHE_ASYNC), "1"), IsNull.notNullValue());
            RetryAssert.assertBy(4, TimeUnit.SECONDS, RetryAssert.elementAt(managerB.getCache(SAMPLE_CACHE_ASYNC), "1"), IsNull.notNullValue());
            assertNull("Element 1 should be null because CacheManager C should not be listening", managerC.getCache(SAMPLE_CACHE_ASYNC).get("1"));
        } finally {
            managerA.shutdown();
            managerB.shutdown();
            managerC.shutdown();
        }
    }
}
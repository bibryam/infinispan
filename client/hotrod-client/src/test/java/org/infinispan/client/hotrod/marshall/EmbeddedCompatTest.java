package org.infinispan.client.hotrod.marshall;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.client.hotrod.TestHelper;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.query.testdomain.protobuf.AccountPB;
import org.infinispan.client.hotrod.query.testdomain.protobuf.marshallers.MarshallerRegistration;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.Index;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.embedded.testdomain.Account;
import org.infinispan.query.dsl.embedded.testdomain.hsearch.AccountHS;
import org.infinispan.query.remote.CompatibilityProtoStreamMarshaller;
import org.infinispan.query.remote.ProtobufMetadataManager;
import org.infinispan.server.hotrod.HotRodServer;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterTest;
import org.testng.annotations.Test;

import java.util.Date;
import java.util.List;

import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.killRemoteCacheManager;
import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.killServers;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.hotRodCacheConfiguration;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

/**
 * Tests compatibility between remote query and embedded mode.
 *
 * @author anistor@redhat.com
 * @since 6.0
 */
@Test(testName = "client.hotrod.marshall.EmbeddedCompatTest", groups = "functional")
@CleanupAfterMethod
public class EmbeddedCompatTest extends SingleCacheManagerTest {

   private HotRodServer hotRodServer;
   private RemoteCacheManager remoteCacheManager;
   private RemoteCache<Integer, Account> remoteCache;

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      org.infinispan.configuration.cache.ConfigurationBuilder builder = createConfigBuilder();
      // the default key equivalence works only for byte[] so we need to override it with one that works for Object
      builder.dataContainer().keyEquivalence(AnyEquivalence.getInstance());

      cacheManager = TestCacheManagerFactory.createCacheManager(builder);
      cache = cacheManager.getCache();

      hotRodServer = TestHelper.startHotRodServer(cacheManager);

      ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
      clientBuilder.addServer().host("127.0.0.1").port(hotRodServer.getPort());
      clientBuilder.marshaller(new ProtoStreamMarshaller());
      remoteCacheManager = new RemoteCacheManager(clientBuilder.build());

      remoteCache = remoteCacheManager.getCache();

      //initialize client-side serialization context
      MarshallerRegistration.registerMarshallers(ProtoStreamMarshaller.getSerializationContext(remoteCacheManager));

      //initialize server-side serialization context
      RemoteCache<String, String> metadataCache = remoteCacheManager.getCache(ProtobufMetadataManager.PROTOBUF_METADATA_CACHE_NAME);
      metadataCache.put("google/protobuf/descriptor.proto", Util.read(Util.getResourceAsStream("/google/protobuf/descriptor.proto", getClass().getClassLoader())));
      metadataCache.put("infinispan/indexing.proto", Util.read(Util.getResourceAsStream("/infinispan/indexing.proto", getClass().getClassLoader())));
      metadataCache.put("sample_bank_account/bank.proto", Util.read(Util.getResourceAsStream("/sample_bank_account/bank.proto", getClass().getClassLoader())));

      ProtobufMetadataManager protobufMetadataManager = cacheManager.getGlobalComponentRegistry().getComponent(ProtobufMetadataManager.class);
      protobufMetadataManager.registerMarshaller(new EmbeddedAccountMarshaller());

      return cacheManager;
   }

   protected org.infinispan.configuration.cache.ConfigurationBuilder createConfigBuilder() {
      org.infinispan.configuration.cache.ConfigurationBuilder builder = hotRodCacheConfiguration();
      builder.compatibility().enable().marshaller(new CompatibilityProtoStreamMarshaller());
      builder.indexing().index(Index.ALL)
            .addProperty("default.directory_provider", "ram")
            .addProperty("lucene_version", "LUCENE_CURRENT");
      return builder;
   }

   @AfterTest
   public void release() {
      killRemoteCacheManager(remoteCacheManager);
      killServers(hotRodServer);
   }

   public void testPutAndGet() throws Exception {
      Account account = createAccount();
      remoteCache.put(1, account);

      // try to get the object through the local cache interface and check it's the same object we put
      assertEquals(1, cache.keySet().size());
      Object key = cache.keySet().iterator().next();
      Object localObject = cache.get(key);
      assertEmbeddedAccount((Account) localObject);

      // get the object through the remote cache interface and check it's the same object we put
      Account fromRemoteCache = remoteCache.get(1);
      assertRemoteAccount(fromRemoteCache);
   }

   public void testPutAndGetForEmbeddedEntry() throws Exception {
      AccountHS account = new AccountHS();
      account.setId(1);
      account.setDescription("test description");
      account.setCreationDate(new Date(42));
      cache.put(1, account);

      // try to get the object through the local cache interface and check it's the same object we put
      assertEquals(1, remoteCache.keySet().size());
      Object key = remoteCache.keySet().iterator().next();
      Object remoteObject = remoteCache.get(key);
      assertRemoteAccount((Account) remoteObject);

      // get the object through the embedded cache interface and check it's the same object we put
      Account fromEmbeddedCache = (Account) cache.get(1);
      assertEmbeddedAccount(fromEmbeddedCache);
   }

   public void testRemoteQuery() throws Exception {
      Account account = createAccount();
      remoteCache.put(1, account);

      // get account back from remote cache via query and check its attributes
      QueryFactory qf = Search.getQueryFactory(remoteCache);
      Query query = qf.from(AccountPB.class)
            .having("description").like("%test%").toBuilder()
            .build();
      List<Account> list = query.list();

      assertNotNull(list);
      assertEquals(1, list.size());
      assertRemoteAccount(list.get(0));
   }

   public void testRemoteQueryForEmbeddedEntry() throws Exception {
      AccountHS account = new AccountHS();
      account.setId(1);
      account.setDescription("test description");
      account.setCreationDate(new Date(42));
      cache.put(1, account);

      // get account back from remote cache via query and check its attributes
      QueryFactory qf = Search.getQueryFactory(remoteCache);
      Query query = qf.from(AccountPB.class)
            .having("description").like("%test%").toBuilder()
            .build();
      List<AccountPB> list = query.list();

      assertNotNull(list);
      assertEquals(1, list.size());
      assertRemoteAccount(list.get(0));
   }

   public void testRemoteQueryWithProjectionsForEmbeddedEntry() throws Exception {
      AccountHS account = new AccountHS();
      account.setId(1);
      account.setDescription("test description");
      account.setCreationDate(new Date(42));
      cache.put(1, account);

      // get account back from remote cache via query and check its attributes
      QueryFactory qf = Search.getQueryFactory(remoteCache);
      Query query = qf.from(AccountPB.class)
            .setProjection("description", "id")
            .having("description").like("%test%").toBuilder()
            .build();
      List<Object[]> list = query.list();

      assertNotNull(list);
      assertEquals(1, list.size());
      assertEquals("test description", list.get(0)[0]);
      assertEquals(1, list.get(0)[1]);
   }

   public void testEmbeddedQuery() throws Exception {
      Account account = createAccount();
      remoteCache.put(1, account);

      // get account back from local cache via query and check its attributes
      org.apache.lucene.search.Query query = org.infinispan.query.Search.getSearchManager(cache)
            .buildQueryBuilderForClass(AccountHS.class).get()
            .keyword().wildcard().onField("description").matching("*test*").createQuery();
      CacheQuery cacheQuery = org.infinispan.query.Search.getSearchManager(cache).getQuery(query);
      List<Object> list = cacheQuery.list();

      assertNotNull(list);
      assertEquals(1, list.size());
      assertEmbeddedAccount((Account) list.get(0));
   }

   private AccountPB createAccount() {
      AccountPB account = new AccountPB();
      account.setId(1);
      account.setDescription("test description");
      account.setCreationDate(new Date(42));
      return account;
   }

   private void assertRemoteAccount(Account account) {
      assertNotNull(account);
      assertEquals(AccountPB.class, account.getClass());
      assertEquals(1, account.getId());
      assertEquals("test description", account.getDescription());
      assertEquals(42, account.getCreationDate().getTime());
   }

   private void assertEmbeddedAccount(Account account) {
      assertEquals(AccountHS.class, account.getClass());
      assertEquals(1, account.getId());
      assertEquals("test description", account.getDescription());
      assertEquals(42, account.getCreationDate().getTime());
   }
}

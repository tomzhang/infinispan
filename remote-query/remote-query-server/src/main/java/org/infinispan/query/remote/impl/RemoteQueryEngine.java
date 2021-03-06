package org.infinispan.query.remote.impl;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.hibernate.hql.ast.spi.EntityNamesResolver;
import org.hibernate.hql.lucene.LuceneProcessingChain;
import org.hibernate.hql.lucene.internal.builder.ClassBasedLucenePropertyHelper;
import org.hibernate.hql.lucene.spi.FieldBridgeProvider;
import org.hibernate.search.bridge.FieldBridge;
import org.hibernate.search.bridge.builtin.NumericFieldBridge;
import org.hibernate.search.bridge.builtin.StringBridge;
import org.hibernate.search.bridge.builtin.impl.NullEncodingTwoWayFieldBridge;
import org.hibernate.search.bridge.builtin.impl.TwoWayString2FieldBridgeAdaptor;
import org.infinispan.AdvancedCache;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.objectfilter.impl.BaseMatcher;
import org.infinispan.objectfilter.impl.ProtobufMatcher;
import org.infinispan.objectfilter.impl.hql.FilterParsingResult;
import org.infinispan.objectfilter.impl.syntax.BooleShannonExpansion;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.descriptors.Descriptor;
import org.infinispan.protostream.descriptors.FieldDescriptor;
import org.infinispan.query.SearchManager;
import org.infinispan.query.dsl.embedded.impl.JPAFilterAndConverter;
import org.infinispan.query.dsl.embedded.impl.QueryEngine;
import org.infinispan.query.remote.impl.filter.JPAProtobufFilterAndConverter;
import org.infinispan.query.remote.impl.indexing.IndexingMetadata;
import org.infinispan.query.remote.impl.indexing.ProtobufValueWrapper;
import org.infinispan.query.remote.impl.logging.Log;

import java.util.Arrays;
import java.util.Map;

/**
 * @author anistor@redhat.com
 * @since 8.0
 */
final class RemoteQueryEngine extends QueryEngine {

   private static final Log log = LogFactory.getLog(RemoteQueryEngine.class, Log.class);

   private static final FieldBridge DOUBLE_FIELD_BRIDGE = new NullEncodingTwoWayFieldBridge(NumericFieldBridge.DOUBLE_FIELD_BRIDGE, QueryFacadeImpl.NULL_TOKEN_CODEC);

   private static final FieldBridge FLOAT_FIELD_BRIDGE = new NullEncodingTwoWayFieldBridge(NumericFieldBridge.FLOAT_FIELD_BRIDGE, QueryFacadeImpl.NULL_TOKEN_CODEC);

   private static final FieldBridge LONG_FIELD_BRIDGE = new NullEncodingTwoWayFieldBridge(NumericFieldBridge.LONG_FIELD_BRIDGE, QueryFacadeImpl.NULL_TOKEN_CODEC);

   private static final FieldBridge INT_FIELD_BRIDGE = new NullEncodingTwoWayFieldBridge(NumericFieldBridge.INT_FIELD_BRIDGE, QueryFacadeImpl.NULL_TOKEN_CODEC);

   private static final FieldBridge STRING_FIELD_BRIDGE = new NullEncodingTwoWayFieldBridge(new TwoWayString2FieldBridgeAdaptor(StringBridge.INSTANCE), QueryFacadeImpl.NULL_TOKEN_CODEC);
 
   private final boolean isCompatMode;

   private final SerializationContext serCtx;

   public RemoteQueryEngine(AdvancedCache<?, ?> cache, SearchManager searchManager, boolean isCompatMode, SerializationContext serCtx) {
      super(cache, searchManager);
      this.isCompatMode = isCompatMode;
      this.serCtx = serCtx;
   }

   @Override
   protected BaseMatcher getFirstPhaseMatcher() {
      Class<? extends BaseMatcher> matcherImplClass = isCompatMode ? CompatibilityReflectionMatcher.class : ProtobufMatcher.class;
      return SecurityActions.getCacheComponentRegistry(cache).getComponent(matcherImplClass);
   }

   @Override
   protected BaseMatcher getSecondPhaseMatcher() {
      // results are already in protobuf format due to type converter interceptor even if we are in compat mode ...
      return SecurityActions.getCacheComponentRegistry(cache).getComponent(ProtobufMatcher.class);
   }

   @Override
   protected org.apache.lucene.search.Query makeTypeQuery(org.apache.lucene.search.Query query, String targetEntityName) {
      if (isCompatMode) {
         return query;
      }
      BooleanQuery booleanQuery = new BooleanQuery();
      booleanQuery.add(new BooleanClause(new TermQuery(new Term(QueryFacadeImpl.TYPE_FIELD_NAME, targetEntityName)), BooleanClause.Occur.MUST));
      booleanQuery.add(new BooleanClause(query, BooleanClause.Occur.MUST));
      return booleanQuery;
   }

   @Override
   protected JPAFilterAndConverter createFilter(String jpaQuery, Map<String, Object> namedParameters) {
      return searchManager != null && !isCompatMode ? new JPAProtobufFilterAndConverter(jpaQuery, namedParameters) :
               new JPAFilterAndConverter(jpaQuery, namedParameters, isCompatMode ? CompatibilityReflectionMatcher.class : ProtobufMatcher.class);
   }

   @Override
   protected BooleShannonExpansion.IndexedFieldProvider getIndexedFieldProvider(FilterParsingResult<?> parsingResult) {
      return isCompatMode ? super.getIndexedFieldProvider(parsingResult) :
            new ProtobufIndexedFieldProvider((Descriptor) parsingResult.getTargetEntityMetadata());
   }

   @Override
   protected LuceneProcessingChain makeProcessingChain(Map<String, Object> namedParameters) {
      LuceneProcessingChain processingChain;
      if (isCompatMode) {
         final EntityNamesResolver entityNamesResolver = new EntityNamesResolver() {
            @Override
            public Class<?> getClassFromName(String entityName) {
               return serCtx.canMarshall(entityName) ? serCtx.getMarshaller(entityName).getJavaClass() : null;
            }
         };

         FieldBridgeProvider fieldBridgeProvider = new FieldBridgeProvider() {

            private final ClassBasedLucenePropertyHelper propertyHelper = new ClassBasedLucenePropertyHelper(searchFactory, entityNamesResolver);

            @Override
            public FieldBridge getFieldBridge(String type, String propertyPath) {
               return propertyHelper.getFieldBridge(type, Arrays.asList(propertyPath.split("[.]")));
            }
         };

         processingChain = new LuceneProcessingChain.Builder(searchFactory, entityNamesResolver)
               .namedParameters(namedParameters)
               .buildProcessingChainForClassBasedEntities(fieldBridgeProvider);
      } else {
         EntityNamesResolver entityNamesResolver = new EntityNamesResolver() {
            @Override
            public Class<?> getClassFromName(String entityName) {
               return serCtx.canMarshall(entityName) ? ProtobufValueWrapper.class : null;
            }
         };

         FieldBridgeProvider fieldBridgeProvider = new FieldBridgeProvider() {
            @Override
            public FieldBridge getFieldBridge(String type, String propertyPath) {
               FieldDescriptor fd = getFieldDescriptor(serCtx, type, propertyPath);
               switch (fd.getType()) {
                  case DOUBLE:
                     return DOUBLE_FIELD_BRIDGE;
                  case FLOAT:
                     return FLOAT_FIELD_BRIDGE;
                  case INT64:
                  case UINT64:
                  case FIXED64:
                  case SFIXED64:
                  case SINT64:
                     return LONG_FIELD_BRIDGE;
                  case INT32:
                  case FIXED32:
                  case UINT32:
                  case SFIXED32:
                  case SINT32:
                  case BOOL:
                  case ENUM:
                     return INT_FIELD_BRIDGE;
                  case STRING:
                  case BYTES:
                  case GROUP:
                  case MESSAGE:
                     return STRING_FIELD_BRIDGE;
               }
               return null;
            }
         };

         processingChain = new LuceneProcessingChain.Builder(searchFactory, entityNamesResolver)
               .namedParameters(namedParameters)
               .buildProcessingChainForDynamicEntities(fieldBridgeProvider);
      }
      return processingChain;
   }

   private FieldDescriptor getFieldDescriptor(SerializationContext serCtx, String type, String attributePath) {
      Descriptor messageDescriptor = serCtx.getMessageDescriptor(type);
      FieldDescriptor fd = null;
      String[] split = attributePath.split("[.]");
      for (int i = 0; i < split.length; i++) {
         String name = split[i];
         fd = messageDescriptor.findFieldByName(name);
         if (fd == null) {
            throw log.unknownField(name, messageDescriptor.getFullName());
         }
         IndexingMetadata indexingMetadata = messageDescriptor.getProcessedAnnotation(IndexingMetadata.INDEXED_ANNOTATION);
         if (indexingMetadata != null && !indexingMetadata.isFieldIndexed(fd.getNumber())) {
            throw log.fieldIsNotIndexed(name, messageDescriptor.getFullName());
         }
         if (i < split.length - 1) {
            messageDescriptor = fd.getMessageType();
         }
      }
      return fd;
   }
}

package org.mrgeo.data.vector.geowave;

import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import mil.nga.giat.geowave.adapter.vector.FeatureDataAdapter;
import mil.nga.giat.geowave.adapter.vector.query.cql.CQLQuery;
import mil.nga.giat.geowave.core.geotime.store.query.*;
import mil.nga.giat.geowave.core.store.DataStore;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatistics;
import mil.nga.giat.geowave.core.store.adapter.statistics.RowRangeHistogramStatistics;
import mil.nga.giat.geowave.core.store.dimension.NumericDimensionField;
import mil.nga.giat.geowave.core.store.index.*;
import mil.nga.giat.geowave.core.store.query.DistributableQuery;
import mil.nga.giat.geowave.core.store.query.QueryOptions;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloDataStore;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloOperations;
import mil.nga.giat.geowave.datastore.accumulo.BasicAccumuloOperations;
import mil.nga.giat.geowave.datastore.accumulo.index.secondary.AccumuloSecondaryIndexDataStore;
import mil.nga.giat.geowave.datastore.accumulo.mapreduce.GeoWaveAccumuloRecordReader;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloAdapterStore;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloDataStatisticsStore;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloIndexStore;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.adapter.AdapterStore;
import mil.nga.giat.geowave.core.store.adapter.DataAdapter;
import mil.nga.giat.geowave.core.store.adapter.statistics.CountDataStatistics;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatisticsStore;
import mil.nga.giat.geowave.core.store.query.BasicQuery;
import mil.nga.giat.geowave.core.store.query.Query;
import mil.nga.giat.geowave.mapreduce.input.GeoWaveInputFormat;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.util.ClassUtil;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.mrgeo.data.DataProviderException;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.vector.*;
import org.mrgeo.geometry.Geometry;
import org.opengis.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class GeoWaveVectorDataProvider extends VectorDataProvider
{
  static Logger log = LoggerFactory.getLogger(GeoWaveVectorDataProvider.class);

  private static final String PROVIDER_PROPERTIES_SIZE = GeoWaveVectorDataProvider.class.getName() + "providerProperties.size";
  private static final String PROVIDER_PROPERTIES_KEY_PREFIX = GeoWaveVectorDataProvider.class.getName() + "providerProperties.key";
  private static final String PROVIDER_PROPERTIES_VALUE_PREFIX = GeoWaveVectorDataProvider.class.getName() + "providerProperties.value";

  private static Map<String, DataSourceEntry> dataSourceEntries = new HashMap<String, DataSourceEntry>();
  private static GeoWaveConnectionInfo connectionInfo;

  // Package private for unit testing
  static boolean initialized = false;

  private DataAdapter<?> dataAdapter;
  private PrimaryIndex primaryIndex;
  private DistributableQuery query;
  private Filter filter;
  private String cqlFilter;
  private com.vividsolutions.jts.geom.Geometry spatialConstraint;
  private Date startTimeConstraint;
  private Date endTimeConstraint;
  private String requestedIndexName;
  private GeoWaveVectorMetadataReader metaReader;
  private ProviderProperties providerProperties;

  private static class ParseResults
  {
    public String namespace;
    public String name;
    public Map<String, String> settings = new HashMap<String, String>();
  }

  public GeoWaveVectorDataProvider(String inputPrefix, String input, ProviderProperties providerProperties)
  {
    super(inputPrefix, input);
    // This constructor is only called from driver-side (i.e. not in
    // map/reduce tasks), so the connection settings are obtained from
    // the mrgeo.conf file.
    getConnectionInfo(); // initializes connectionInfo if needed
    this.providerProperties = providerProperties;
  }

  public static GeoWaveConnectionInfo getConnectionInfo()
  {
    if (connectionInfo == null)
    {
      connectionInfo = GeoWaveConnectionInfo.load();
    }
    return connectionInfo;
  }

  static void setConnectionInfo(GeoWaveConnectionInfo connInfo)
  {
    connectionInfo = connInfo;
  }

  public AdapterStore getAdapterStore() throws AccumuloSecurityException, AccumuloException, IOException
  {
    String namespace = getNamespace();
    initDataSource(namespace);
    DataSourceEntry entry = getDataSourceEntry(namespace);
    return entry.adapterStore;
  }

  public DataStore getDataStore() throws AccumuloSecurityException, AccumuloException, IOException
  {
    String namespace = getNamespace();
    initDataSource(namespace);
    DataSourceEntry entry = getDataSourceEntry(namespace);
    return entry.dataStore;
  }

  public DataStatisticsStore getStatisticsStore() throws AccumuloSecurityException, AccumuloException, IOException
  {
    String namespace = getNamespace();
    initDataSource(namespace);
    DataSourceEntry entry = getDataSourceEntry(namespace);
    return entry.statisticsStore;
  }

  public PrimaryIndex getPrimaryIndex() throws AccumuloSecurityException, AccumuloException, IOException
  {
    if (primaryIndex != null)
    {
      return primaryIndex;
    }
    String namespace = getNamespace();
    String resourceName = getGeoWaveResourceName();
    initDataSource(namespace);
    DataSourceEntry entry = getDataSourceEntry(namespace);
    CloseableIterator<Index<?, ?>> indices = entry.indexStore.getIndices();
    try
    {
      DistributableQuery query = getQuery();
      while (indices.hasNext())
      {
        PrimaryIndex idx = (PrimaryIndex)indices.next();
        String indexName = idx.getId().getString();
        log.debug("Checking GeoWave index " + idx.getId().getString());
        NumericDimensionField<? extends CommonIndexValue>[] dimFields = idx.getIndexModel().getDimensions();
        for (NumericDimensionField<? extends CommonIndexValue> dimField : dimFields)
        {
          log.debug("  Dimension field: " + dimField.getFieldId().getString());
        }

        if (getStatisticsStore().getDataStatistics(
                getDataAdapter().getAdapterId(),
                RowRangeHistogramStatistics.composeId(idx.getId()),
                getAuthorizations(providerProperties)) != null)
        {
          log.debug("  Index stores data for " + resourceName);
          if (requestedIndexName != null && !requestedIndexName.isEmpty())
          {
            // The user requested a specific index. See if this is it, and then
            // make sure it supports the query criteria if there is any.
            if (indexName.equalsIgnoreCase(requestedIndexName))
            {
              log.debug("  Index matches the requested index");
              // Make sure if there is query criteria that the requested index can support
              // that query.
              if (query != null && !query.isSupported(idx))
              {
                throw new IOException(
                        "The requested index " + requestedIndexName + " does not support your query criteria");
              }
              primaryIndex = idx;
              return primaryIndex;
            }
          }
          else
          {
            // If there is no query, then just use the first index for this adapter
            if (query == null)
            {
              log.debug("  Since there is no query, we will use this index");
              primaryIndex = idx;
              return primaryIndex;
            }
            else
            {
              // Make sure the index supports the query, and get the number of fields
              // in the index. We want to use the index with the most fields.
              if (query.isSupported(idx))
              {
                log.debug("  This index does support this query " + query.getClass().getName());
                primaryIndex = idx;
                return primaryIndex;
              }
              else
              {
                log.debug("  This index does not support this query " + query.getClass().getName());
              }
            }
          }
        }
        else
        {
          // If the index does not contain data for the adapter, but it is the requested index
          // then report an error to the user.
          if (requestedIndexName != null && indexName.equalsIgnoreCase(requestedIndexName))
          {
            throw new IOException("The requested index " + requestedIndexName + " does not include " + resourceName);
          }
        }
      }
    }
    finally
    {
      indices.close();
    }
    if (primaryIndex != null)
    {
      return primaryIndex;
    }
    throw new IOException("Unable to find GeoWave index for adapter " + resourceName);
  }

  public String getGeoWaveResourceName() throws IOException
  {
    ParseResults results = parseResourceName(getResourceName());
    return results.name;
  }

  public String getNamespace() throws IOException
  {
    ParseResults results = parseResourceName(getResourceName());
    return results.namespace;
  }

  public DataAdapter<?> getDataAdapter() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return dataAdapter;
  }

  /**
   * Returns the CQL filter for this data provider. If no filtering is required, then this will
   * return a null value.
   *
   * @return
   * @throws AccumuloSecurityException
   * @throws AccumuloException
   * @throws IOException
   */
  public String getCqlFilter() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return cqlFilter;
  }

  public com.vividsolutions.jts.geom.Geometry getSpatialConstraint() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return spatialConstraint;
  }

  public Date getStartTimeConstraint() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return startTimeConstraint;
  }

  public Date getEndTimeConstraint() throws AccumuloSecurityException, AccumuloException, IOException
  {
    init();
    return endTimeConstraint;
  }

  public DistributableQuery getQuery() throws AccumuloSecurityException, AccumuloException, IOException
  {
    if (query != null)
    {
      return query;
    }
    com.vividsolutions.jts.geom.Geometry spatialConstraint = getSpatialConstraint();
    Date startTimeConstraint = getStartTimeConstraint();
    Date endTimeConstraint = getEndTimeConstraint();
    if ((startTimeConstraint == null) != (endTimeConstraint == null))
    {
      throw new DataProviderException("When querying a GeoWave data source by time," +
                                      " both the start and the end are required.");
    }
    if (spatialConstraint != null)
    {
      if (startTimeConstraint != null || endTimeConstraint != null)
      {
        log.debug("Using GeoWave SpatialTemporalQuery");
        TemporalConstraints tc = getTemporalConstraints(startTimeConstraint, endTimeConstraint);
        SpatialTemporalQuery stq = new SpatialTemporalQuery(tc, spatialConstraint);
        query = new SpatialTemporalQuery(tc, spatialConstraint);
      }
      else
      {
        log.debug("Using GeoWave SpatialQuery");
        query = new SpatialQuery(spatialConstraint);
      }
    }
    else
    {
      if (startTimeConstraint != null || endTimeConstraint != null)
      {
        log.debug("Using GeoWave TemporalQuery");
        TemporalConstraints tc = getTemporalConstraints(startTimeConstraint, endTimeConstraint);
        query = new TemporalQuery(tc);
      }
    }
    return query;
  }

  private String[] getAuthorizations(ProviderProperties providerProperties)
  {
    List<String> userRoles = null;
    if (providerProperties != null)
    {
      userRoles = providerProperties.getRoles();
    }
    if (userRoles != null)
    {
      String[] auths = new String[userRoles.size()];
      for (int i = 0; i < userRoles.size(); i++)
      {
        auths[i] = userRoles.get(i).trim();
      }
      return auths;
    }
    return null;
  }

  public QueryOptions getQueryOptions(ProviderProperties providerProperties) throws AccumuloSecurityException, AccumuloException, IOException
  {
    QueryOptions queryOptions = new QueryOptions(getDataAdapter(), getPrimaryIndex());
    String[] auths = getAuthorizations(providerProperties);
    if (auths != null)
    {
      queryOptions.setAuthorizations(auths);
    }
    return queryOptions;
  }

  private TemporalConstraints getTemporalConstraints(Date startTime, Date endTime)
  {
    TemporalRange tr = new TemporalRange();
    if (startTime != null)
    {
      tr.setStartTime(startTime);
    }
    if (endTime != null)
    {
      tr.setEndTime(endTime);
    }
    TemporalConstraints tc = new TemporalConstraints();
    tc.add(tr);
    return tc;
  }

  /**
   * Parses the input string into the optional namespace, the name of the input and
   * the optional query string
   * that accompanies it. The two strings are separated by a semi-colon, and the query
   * should be included in double quotes if it contains any semi-colons or square brackets.
   * But the double quotes are not required otherwise.
   *
   * @param input
   * @return
   */
  private static ParseResults parseResourceName(String input) throws IOException
  {
    int semiColonIndex = input.indexOf(';');
    if (semiColonIndex == 0)
    {
      throw new IOException("Missing name from GeoWave data source: " + input);
    }
    int dotIndex = input.indexOf('.');
    ParseResults results = new ParseResults();
    if (semiColonIndex > 0)
    {
      if (dotIndex >= 0)
      {
        results.namespace = input.substring(0, dotIndex);
        results.name = input.substring(dotIndex + 1, semiColonIndex);
      }
      else
      {
        results.name = input.substring(0, semiColonIndex);
      }
      // Start parsing the data source settings
      String strSettings = input.substring(semiColonIndex + 1);
      // Now parse each property of the geowave source.
      parseDataSourceSettings(strSettings, results.settings);
    }
    else
    {
      if (dotIndex >= 0)
      {
        results.namespace = input.substring(0, dotIndex);
        results.name = input.substring(dotIndex + 1);
      }
      else
      {
        results.name = input;
      }
    }
    // If there is no namespace explicitly in the resource name, then we
    // check to make sure the GeoWave configuration for MrGeo only has one
    // namespace specified and use it. If there are multiple namespaces
    // configured, then throw an exception.
    if (results.namespace == null || results.namespace.isEmpty())
    {
      GeoWaveConnectionInfo connectionInfo = getConnectionInfo();
      String[] namespaces = connectionInfo.getNamespaces();
      if (namespaces == null || namespaces.length == 0)
      {
        throw new IOException("Missing missing " + GeoWaveConnectionInfo.GEOWAVE_NAMESPACES_KEY +
                              " in the MrGeo configuration");
      }
      else
      {
        if (namespaces.length > 1)
        {
          throw new IOException(
                  "You must specify a GeoWave namespace for " + input +
                  " because multiple GeoWave namespaces are configured in MrGeo (e.g." +
                  " MyNamespace." + input + ")");
        }
        results.namespace = namespaces[0];
      }
    }
    return results;
  }

  // Package private for unit testing
  static void parseDataSourceSettings(String strSettings,
                                              Map<String, String> settings) throws IOException
  {
    boolean foundSemiColon = true;
    String remaining = strSettings.trim();
    if (remaining.isEmpty())
    {
      return;
    }

    int settingIndex = 0;
    while (foundSemiColon)
    {
      int equalsIndex = remaining.indexOf("=", settingIndex);
      if (equalsIndex >= 0)
      {
        String keyName = remaining.substring(settingIndex, equalsIndex).trim();
        // Everything after the = char
        remaining = remaining.substring(equalsIndex + 1).trim();
        if (remaining.length() > 0)
        {
          // Handle double-quoted settings specially, skipping escaped double
          // quotes inside the value.
          if (remaining.startsWith("\""))
          {
            // Find the index of the corresponding closing quote. Note that double
            // quotes can be escaped with a backslash (\) within the quoted string.
            int closingQuoteIndex = remaining.indexOf('"', 1);
            while (closingQuoteIndex > 0)
            {
              // If the double quote is not preceeded by an escape backslash,
              // then we've found the closing quote.
              if (remaining.charAt(closingQuoteIndex - 1) != '\\')
              {
                break;
              }
              closingQuoteIndex = remaining.indexOf('"', closingQuoteIndex + 1);
            }
            if (closingQuoteIndex >= 0)
            {
              String value = remaining.substring(1, closingQuoteIndex);
              log.debug("Adding GeoWave source key setting " + keyName + " = " + value);
              settings.put(keyName, value);
              settingIndex = 0;
              int nextSemiColonIndex = remaining.indexOf(';', closingQuoteIndex + 1);
              if (nextSemiColonIndex >= 0)
              {
                foundSemiColon = true;
                remaining = remaining.substring(nextSemiColonIndex + 1).trim();
              }
              else
              {
                // No more settings
                foundSemiColon = false;
              }
            }
            else
            {
              throw new IOException("Invalid GeoWave settings string, expected ending double quote for key " +
                                    keyName + " in " + strSettings);
            }
          }
          else
          {
            // The value is not quoted
            int semiColonIndex = remaining.indexOf(";");
            if (semiColonIndex >= 0)
            {
              String value = remaining.substring(0, semiColonIndex);
              log.debug("Adding GeoWave source key setting " + keyName + " = " + value);
              settings.put(keyName, value);
              settingIndex = 0;
              remaining = remaining.substring(semiColonIndex + 1);
            }
            else
            {
              log.debug("Adding GeoWave source key setting " + keyName + " = " + remaining);
              settings.put(keyName, remaining);
              // There are no more settings since there are no more semi-colons
              foundSemiColon = false;
            }
          }
        }
        else
        {
          throw new IOException("Missing value for " + keyName);
        }
      }
      else
      {
        throw new IOException("Invalid syntax. No value assignment in \"" + remaining + "\"");
      }
    }
  }

  @Override
  public VectorMetadataReader getMetadataReader()
  {
    if (metaReader == null)
    {
      metaReader = new GeoWaveVectorMetadataReader(this);
    }
    return metaReader;
  }

  @Override
  public VectorMetadataWriter getMetadataWriter()
  {
    // Not yet implemented
    return null;
  }

  @Override
  public VectorReader getVectorReader() throws IOException
  {
    ParseResults results = parseResourceName(getResourceName());
    try
    {
      init(results);
    }
    catch (AccumuloSecurityException e)
    {
      throw new IOException("AccumuloSecurityException in GeoWave data provider getVectorReader", e);
    }
    catch (AccumuloException e)
    {
      throw new IOException("AccumuloException in GeoWave data provider getVectorReader", e);
    }
    DataSourceEntry entry = getDataSourceEntry(results.namespace);
    Query query = new BasicQuery(new BasicQuery.Constraints());
    CQLQuery cqlQuery = new CQLQuery(query, filter,
                                     (FeatureDataAdapter)entry.adapterStore.getAdapter(new ByteArrayId(this.getGeoWaveResourceName())));
    try
    {
      return new GeoWaveVectorReader(results.namespace, entry.dataStore,
                                     entry.adapterStore.getAdapter(new ByteArrayId(this.getGeoWaveResourceName())),
                                     cqlQuery, getPrimaryIndex(), filter, providerProperties);
    }
    catch (AccumuloSecurityException e)
    {
      throw new IOException(e);
    }
    catch (AccumuloException e)
    {
      throw new IOException(e);
    }
  }

  @Override
  public VectorReader getVectorReader(VectorReaderContext context) throws IOException
  {
    return getVectorReader();
  }

  @Override
  public VectorWriter getVectorWriter()
  {
    // Not yet implemented
    return null;
  }

  @Override
  public RecordReader<FeatureIdWritable, Geometry> getRecordReader() throws IOException
  {
    ParseResults results = parseResourceName(getResourceName());
    DistributableQuery query = null;
    try
    {
      init(results);
      query = getQuery();
      DataSourceEntry entry = getDataSourceEntry(results.namespace);
      DataAdapter<?> adapter = getDataAdapter();
      RecordReader delegateRecordReader =  new GeoWaveAccumuloRecordReader(
              query,
              new QueryOptions(adapter, getPrimaryIndex(), getAuthorizations(getProviderProperties())),
              false,
              entry.adapterStore,
              entry.storeOperations);
      return new GeoWaveVectorRecordReader(delegateRecordReader);
    }
    catch (AccumuloSecurityException e)
    {
      throw new IOException("AccumuloSecurityException in GeoWave data provider getVectorReader", e);
    }
    catch (AccumuloException e)
    {
      throw new IOException("AccumuloException in GeoWave data provider getVectorReader", e);
    }
  }

  @Override
  public RecordWriter<FeatureIdWritable, Geometry> getRecordWriter()
  {
    // Not yet implemented
    return null;
  }

  @Override
  public VectorInputFormatProvider getVectorInputFormatProvider(VectorInputFormatContext context)
  {
    return new GeoWaveVectorInputFormatProvider(context, this);
  }

  @Override
  public VectorOutputFormatProvider getVectorOutputFormatProvider(VectorOutputFormatContext context)
  {
    // Not yet implemented
    return null;
  }

  @Override
  public void delete() throws IOException
  {
    // Not yet implemented
  }

  @Override
  public void move(String toResource) throws IOException
  {
    // Not yet implemented
  }

  public static boolean isValid(Configuration conf)
  {
    initConnectionInfo(conf);
    return (connectionInfo != null);
  }

  public static boolean isValid()
  {
    initConnectionInfo();
    return (connectionInfo != null);
  }

  public static String[] listVectors(final ProviderProperties providerProperties) throws AccumuloException, AccumuloSecurityException, IOException
  {
    initConnectionInfo();
    List<String> results = new ArrayList<String>();
    for (String namespace: connectionInfo.getNamespaces())
    {
      initDataSource(namespace);
      DataSourceEntry entry = getDataSourceEntry(namespace);
      CloseableIterator<DataAdapter<?>> iter = entry.adapterStore.getAdapters();
      try
      {
        while (iter.hasNext())
        {
          DataAdapter<?> adapter = iter.next();
          if (adapter != null)
          {
            ByteArrayId adapterId = adapter.getAdapterId();
            if (checkAuthorizations(adapterId, namespace, providerProperties))
            {
              results.add(adapterId.getString());
            }
          }
        }
      }
      finally
      {
        if (iter != null)
        {
          iter.close();
        }
      }
    }
    String[] resultArray = new String[results.size()];
    return results.toArray(resultArray);
  }

  public static boolean canOpen(String input,
                                ProviderProperties providerProperties) throws AccumuloException, AccumuloSecurityException, IOException
  {
    initConnectionInfo();
    ParseResults results = parseResourceName(input);
    try
    {
      initDataSource(results.namespace);
      DataSourceEntry entry = getDataSourceEntry(results.namespace);
      ByteArrayId adapterId = new ByteArrayId(results.name);
      DataAdapter<?> adapter = entry.adapterStore.getAdapter(adapterId);
      if (adapter == null)
      {
        return false;
      }
      return checkAuthorizations(adapterId, results.namespace, providerProperties);
    }
    catch(IllegalArgumentException e)
    {
      log.info("Unable to open " + input + " with the GeoWave data provider: " + e.getMessage());
    }
    return false;
  }

  private static boolean checkAuthorizations(ByteArrayId adapterId,
                                             String namespace,
      ProviderProperties providerProperties) throws IOException, AccumuloException, AccumuloSecurityException
  {
    // Check to see if the requester is authorized to see any of the data in
    // the adapter.
    return (getAdapterCount(adapterId, namespace, providerProperties) > 0L);
  }

  private static DataSourceEntry getDataSourceEntry(String namespace) throws IOException
  {
    DataSourceEntry entry = dataSourceEntries.get(namespace);
    if (entry == null)
    {
      throw new IOException("Data source was not yet initialized for namespace: " + namespace);
    }
    return entry;
  }

  public static long getAdapterCount(ByteArrayId adapterId,
                                     String namespace,
                                     ProviderProperties providerProperties)
          throws IOException, AccumuloException, AccumuloSecurityException
  {
    initConnectionInfo();
    initDataSource(namespace);
    DataSourceEntry entry = getDataSourceEntry(namespace);
    List<String> roles = null;
    if (providerProperties != null)
    {
      roles = providerProperties.getRoles();
    }
    if (roles != null && roles.size() > 0)
    {
      String auths = StringUtils.join(roles, ",");
      CountDataStatistics<?> count = (CountDataStatistics<?>)entry.statisticsStore.getDataStatistics(adapterId,  CountDataStatistics.STATS_ID, auths);
      if (count != null && count.isSet())
      {
        return count.getCount();
      }
    }
    else
    {
      CountDataStatistics<?> count = (CountDataStatistics<?>)entry.statisticsStore.getDataStatistics(adapterId,  CountDataStatistics.STATS_ID);
      if (count != null && count.isSet())
      {
        return count.getCount();
      }
    }
    return 0L;
  }

  private void init() throws AccumuloSecurityException, AccumuloException, IOException
  {
    // Don't initialize more than once.
    if (initialized)
    {
      return;
    }
    ParseResults results = parseResourceName(getResourceName());
    init(results);
  }

  private void init(ParseResults results) throws AccumuloSecurityException, AccumuloException, IOException
  {
    // Don't initialize more than once.
    if (initialized)
    {
      return;
    }
    initialized = true;
    // Extract the GeoWave adapter name and optional CQL string
    DataSourceEntry entry = getDataSourceEntry(results.namespace);
    // Now perform initialization for this specific data provider (i.e. for
    // this resource).
    dataAdapter = entry.adapterStore.getAdapter(new ByteArrayId(results.name));
    assignSettings(results.name, results.settings);

// Testing code
//    SimpleFeatureType sft = ((FeatureDataAdapter)dataAdapter).getType();
//    int attributeCount = sft.getAttributeCount();
//    System.out.println("attributeCount = " + attributeCount);
//    CloseableIterator<?> iter = dataStore.query(dataAdapter, null);
//    try
//    {
//      while (iter.hasNext())
//      {
//        Object value = iter.next();
//        System.out.println("class is " + value.getClass().getName());
//        System.out.println("value is " + value);
//      }
//    }
//    finally
//    {
//      iter.close();
//    }
  }

  // Package private for unit testing
  void assignSettings(String name, Map<String, String> settings) throws IOException
  {
    filter = null;
    spatialConstraint = null;
    startTimeConstraint = null;
    endTimeConstraint = null;
    for (String keyName : settings.keySet())
    {
      if (keyName != null && !keyName.isEmpty())
      {
        String value = settings.get(keyName);
        switch(keyName)
        {
          case "spatial":
          {
            WKTReader wktReader = new WKTReader();
            try
            {
              spatialConstraint = wktReader.read(value);
            }
            catch (ParseException e)
            {
              throw new IOException("Invalid WKT specified for spatial property of GeoWave data source " +
                                    name);
            }
            break;
          }

          case "startTime":
          {
            startTimeConstraint = parseDate(value);
            break;
          }

          case "endTime":
          {
            endTimeConstraint = parseDate(value);
            break;
          }

          case "cql":
          {
            if (value != null && !value.isEmpty())
            {
              cqlFilter = value;
              try
              {
                filter = ECQL.toFilter(value);
              }
              catch (CQLException e)
              {
                throw new IOException("Bad CQL filter: " + value, e);
              }
            }
            break;
          }
          case "index":
          {
            requestedIndexName = value.trim();
            break;
          }
          default:
            throw new IOException("Unrecognized setting for GeoWave data source " +
                                  name + ": " + keyName);
        }
      }
    }

    if ((startTimeConstraint == null) != (endTimeConstraint == null))
    {
      throw new IOException("When querying a GeoWave data source by time," +
                            " both the start and the end are required.");
    }
    if (startTimeConstraint != null && endTimeConstraint != null && startTimeConstraint.after(endTimeConstraint))
    {
      throw new IOException("For GeoWave data source " + name + ", startDate must be after endDate");
    }
  }

  private Date parseDate(String value)
  {
    DateTimeFormatter formatter = ISODateTimeFormat.dateOptionalTimeParser();
    DateTime dateTime = formatter.parseDateTime(value);
    return dateTime.toDate();
  }

  private static void initConnectionInfo(Configuration conf)
  {
    // The connectionInfo only needs to be set once. It is the same for
    // the duration of the JVM. Note that it is instantiated differently
    // on the driver-side than it is within map/reduce tasks. This method
    // loads connection settings from the job configuration.
    if (connectionInfo == null)
    {
      connectionInfo = GeoWaveConnectionInfo.load(conf);
    }
  }

  private static void initConnectionInfo()
  {
    // The connectionInfo only needs to be set once. It is the same for
    // the duration of the JVM. Note that it is instantiated differently
    // on the driver-side than it is within map/reduce tasks. This method
    // loads connection settings from the mrgeo.conf file.
    if (connectionInfo == null)
    {
      connectionInfo = GeoWaveConnectionInfo.load();
    }
  }

  private static void initDataSource(String namespace) throws AccumuloException, AccumuloSecurityException, IOException
  {
    DataSourceEntry entry = dataSourceEntries.get(namespace);
    if (entry == null)
    {
      entry = new DataSourceEntry();
      dataSourceEntries.put(namespace, entry);
      entry.storeOperations = new BasicAccumuloOperations(
          connectionInfo.getZookeeperServers(),
          connectionInfo.getInstanceName(),
          connectionInfo.getUserName(),
          connectionInfo.getPassword(),
          namespace);
      entry.indexStore = new AccumuloIndexStore(
          entry.storeOperations);
      entry.secondaryIndexStore = new AccumuloSecondaryIndexDataStore(
              entry.storeOperations);
      entry.statisticsStore = new AccumuloDataStatisticsStore(
          entry.storeOperations);
      entry.adapterStore = new AccumuloAdapterStore(
          entry.storeOperations);
      entry.dataStore = new AccumuloDataStore(
          entry.indexStore,
          entry.adapterStore,
          entry.statisticsStore,
          entry.secondaryIndexStore,
          entry.storeOperations);
    }
  }

  static class DataSourceEntry
  {
    public DataSourceEntry()
    {
    }

    private AccumuloOperations storeOperations;
    private IndexStore indexStore;
    private SecondaryIndexDataStore secondaryIndexStore;
    private AdapterStore adapterStore;
    private DataStatisticsStore statisticsStore;
    private DataStore dataStore;
  }
}

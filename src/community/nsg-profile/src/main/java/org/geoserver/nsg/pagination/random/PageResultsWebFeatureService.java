/* (c) 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.nsg.pagination.random;

import net.opengis.wfs20.GetFeatureType;
import net.opengis.wfs20.ResultTypeType;
import org.geoserver.config.GeoServer;
import org.geoserver.ows.Dispatcher;
import org.geoserver.ows.KvpRequestReader;
import org.geoserver.ows.util.OwsUtils;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.Service;
import org.geoserver.platform.resource.Resource;
import org.geoserver.wfs.DefaultWebFeatureService20;
import org.geoserver.wfs.request.FeatureCollectionResponse;
import org.geotools.data.DataStore;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.Transaction;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.util.logging.Logging;
import org.opengis.filter.Filter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 * This service supports the PageResults operation and manage it
 *
 * @author sandr
 *
 */

public class PageResultsWebFeatureService extends DefaultWebFeatureService20 {

    static Logger LOGGER = Logging.getLogger(PageResultsWebFeatureService.class);

    private static final String GML32_FORMAT = "application/gml+xml; version=3.2";

    private static final BigInteger DEFAULT_START = new BigInteger("0");

    private static final BigInteger DEFAULT_COUNT = new BigInteger("10");

    private IndexConfiguration indexConfiguration;

    public PageResultsWebFeatureService(GeoServer geoServer,
            IndexConfiguration indexConfiguration) {
        super(geoServer);
        this.indexConfiguration = indexConfiguration;


    }

    /**
     *
     * Recovers the stored request with associated {@link #resultSetID} and overrides the parameters
     * using the ones provided with current operation or the default values:
     * <ul>
     * <li>{@link net.opengis.wfs20.GetFeatureType#getStartIndex <em>StartIndex</em>}</li>
     * <li>{@link net.opengis.wfs20.GetFeatureType#getCount <em>Count</em>}</li>
     * <li>{@link net.opengis.wfs20.GetFeatureType#getOutputFormat <em>OutputFormat</em>}</li>
     * <li>{@link net.opengis.wfs20.GetFeatureType#getResultType <em>ResultType</em>}</li>
     * </ul>
     * Then executes the GetFeature operation using the WFS 2.0 service implementation and return is
     * result.
     *
     * @param request
     * @return
     * @throws Exception
     */
    public FeatureCollectionResponse pageResults(GetFeatureType request) throws Exception {
        // Retrieve stored request
        String resultSetId = (String) Dispatcher.REQUEST.get().getKvp().get("resultSetID");
        GetFeatureType gft = getFeature(resultSetId);

        // Update with incoming parameters or index request or defaults
        Method setBaseUrl = OwsUtils.setter(gft.getClass(), "baseUrl", String.class);
        setBaseUrl.invoke(gft, new Object[] { request.getBaseUrl() });
        BigInteger startIndex = request.getStartIndex() != null ? request.getStartIndex()
                : gft.getStartIndex() != null ? gft.getStartIndex() : DEFAULT_START;
        BigInteger count = request.getCount() != null ? request.getCount()
                : gft.getCount() != null ? gft.getCount() : DEFAULT_COUNT;
        String outputFormat = request.getOutputFormat() != null ? request.getOutputFormat()
                : GML32_FORMAT;
        ResultTypeType resultType = request.getResultType() != null ? request.getResultType()
                : ResultTypeType.RESULTS;
        gft.setStartIndex(startIndex);
        gft.setCount(count);
        gft.setOutputFormat(outputFormat);
        gft.setResultType(resultType);
        // Execute as getFeature
        return super.getFeature(gft);
    }

    /**
     * Helper method that deserializes GetFeature request and updates its last utilization
     *
     * @param resultSetID
     * @return
     * @throws Exception
     */
    private GetFeatureType getFeature(String resultSetId) throws IOException {
        GetFeatureType feature = null;
        Transaction transaction = new DefaultTransaction("Update");
        try {
            IndexInitializer.READ_WRITE_LOCK.writeLock().lock();
            // Update GetFeature utilization
            DataStore currentDataStore = this.indexConfiguration.getCurrentDataStore();
            SimpleFeatureStore store = (SimpleFeatureStore) currentDataStore
                    .getFeatureSource(IndexInitializer.STORE_SCHEMA_NAME);
            store.setTransaction(transaction);
            Filter filter = CQL.toFilter("ID = '" + resultSetId + "'");
            store.modifyFeatures("updated", new Date().getTime(), filter);
            // Retrieve GetFeature from file
            Resource storageResource = this.indexConfiguration.getStorageResource();

            try (ObjectInputStream is = new ObjectInputStream(new FileInputStream(new File(
                    storageResource.dir(), resultSetId + ".feature")))) {
                RequestData data = (RequestData) is.readObject();
                KvpRequestReader kvpReader = Dispatcher.findKvpRequestReader(GetFeatureType.class);
                Object requestBean = kvpReader.createRequest();
                feature = (GetFeatureType) kvpReader.read(requestBean, data.getKvp(), data.getRawKvp());
            }

        } catch (Exception t) {
            transaction.rollback();
            throw new RuntimeException("Error on retrive feature", t);
        } finally {
            transaction.close();
            IndexInitializer.READ_WRITE_LOCK.writeLock().unlock();
        }
        return feature;

    }

}

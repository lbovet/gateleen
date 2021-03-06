package org.swisspush.gateleen.delta.delta;

import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.CollectionResourceContainer;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling;
import org.swisspush.gateleen.core.util.ResourceCollectionException;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.routing.routing.Router;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DeltaHandler {

    private Logger log = LoggerFactory.getLogger(DeltaHandler.class);

    private static final String DELTA_PARAM = "delta";
    private static final String DELTA_HEADER = "x-delta";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";
    // used as marker header to know that we should let the request continue to the router
    private static final String DELTA_BACKEND_HEADER = "x-delta-backend";
    private static final String EXPIRE_AFTER_HEADER = "X-Expire-After";
    private static final long DEFAULT_EXPIRE = 1728000; // 20 days
    private static final String SLASH = "/";
    private static final int TIMEOUT = 120000;

    private static final String SEQUENCE_KEY = "delta:sequence";
    private static final String RESOURCE_KEY_PREFIX = "delta:resources";
    private static final String ETAG_KEY_PREFIX = "delta:etags";

    private HttpClient httpClient;
    private RedisClient redisClient;

    public DeltaHandler(RedisClient redisClient, HttpClient httpClient) {
        this.redisClient = redisClient;
        this.httpClient = httpClient;
    }

    public boolean isDeltaRequest(HttpServerRequest request) {
        return isDeltaGETRequest(request) || isDeltaPUTRequest(request);
    }

    private boolean isDeltaPUTRequest(HttpServerRequest request) {
        if (HttpMethod.PUT == request.method() && request.headers().contains(DELTA_HEADER)) {
            return "auto".equalsIgnoreCase(request.headers().get(DELTA_HEADER));
        }
        return false;
    }

    private boolean isDeltaGETRequest(HttpServerRequest request) {
        if(HttpMethod.GET == request.method() &&
           request.params().contains(DELTA_PARAM) && 
           !request.headers().contains(DELTA_BACKEND_HEADER)) {
        	return true;
        }
        // remove the delta backend header, its only a marker
        if(request.headers().contains(DELTA_BACKEND_HEADER)) {
        	request.headers().remove(DELTA_BACKEND_HEADER);
        }
        return false;
    }

    public void handle(final HttpServerRequest request, Router router) {
        if (isDeltaPUTRequest(request)) {
            handleResourcePUT(request, router);
        }
        if (isDeltaGETRequest(request)) {
            String updateId = extractStringDeltaParameter(request);
            if (updateId != null) {
                handleCollectionGET(request, updateId);
            }
        }
    }

    private void handleResourcePUT(final HttpServerRequest request, final Router router) {
        request.pause(); // pause the request to avoid problems with starting another async request (storage)
        handleDeltaEtag(request, updateDelta -> {
            if(updateDelta){
                // increment and get update-id
                redisClient.incr(SEQUENCE_KEY, reply -> {
                    if(reply.failed()){
                        log.error("incr command for redisKey " + SEQUENCE_KEY + " failed with cause: " + logCause(reply));
                        handleError(request, "error incrementing/accessing sequence for update-id");
                        return;
                    }

                    final String resourceKey = getResourceKey(request.path(), false);
                    long expireAfter = getExpireAfterValue(request.headers());
                    long updateId = reply.result();

                    // save to storage
                    redisClient.setex(resourceKey, expireAfter, String.valueOf(updateId), event -> {
                        if(event.failed()){
                            log.error("setex command for redisKey " + resourceKey + " failed with cause: " + logCause(event));
                            handleError(request, "error saving delta information");
                            request.resume();
                        } else {
                            request.resume();
                            router.route(request);
                        }
                    });
                });

            } else {
                log.debug("skip updating delta, resume request");
                request.resume();
                router.route(request);
            }
        });
    }

    private void handleDeltaEtag(final HttpServerRequest request, final Handler<Boolean> callback){
        /*
         * When no Etag is provided we just do the delta update
         */
        if(!request.headers().contains(IF_NONE_MATCH_HEADER)){
            callback.handle(Boolean.TRUE);
            return;
        }

        /*
         * Loading Delta-Etag from storage to compare with header
         */
        final String requestEtag = request.headers().get(IF_NONE_MATCH_HEADER);
        final String etagResourceKey = getResourceKey(request.path(), true);
        redisClient.get(etagResourceKey, event -> {
            if(event.failed()){
                log.error("get command for redisKey " + etagResourceKey + " failed with cause: " + logCause(event));
                callback.handle(Boolean.TRUE);
                return;
            }

            String etagFromStorage = event.result();
            if(StringUtils.isEmpty(etagFromStorage)){
                    /*
                     * No Etag entry found. Store it and then do the delta update
                     */
                saveOrUpdateDeltaEtag(etagResourceKey, request, aBoolean -> callback.handle(Boolean.TRUE));
            } else {
                    /*
                     * If etags match, no delta update has to be made.
                     * If not, store/update the etag and then update the delta
                     */
                if(etagFromStorage.equals(requestEtag)){
                    callback.handle(Boolean.FALSE);
                } else {
                    saveOrUpdateDeltaEtag(etagResourceKey, request, aBoolean -> callback.handle(Boolean.TRUE));
                }
            }
        });
    }

    private void saveOrUpdateDeltaEtag(final String etagResourceKey, final HttpServerRequest request, final Handler<Boolean> updateCallback){
        final String requestEtag = request.headers().get(IF_NONE_MATCH_HEADER);
        long expireAfter = getExpireAfterValue(request.headers());
        redisClient.setex(etagResourceKey, expireAfter, requestEtag, event -> {
            if(event.failed()){
                log.error("setex command for redisKey " + etagResourceKey + " failed with cause: " + logCause(event));
            }
            updateCallback.handle(Boolean.TRUE);
        });
    }

    private String extractStringDeltaParameter(HttpServerRequest request) {
	    String updateIdValue = request.params().get(DELTA_PARAM);
	    if(updateIdValue == null) {
	    	request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
	        request.response().setStatusMessage("Invalid delta parameter");
	        request.response().end(request.response().getStatusMessage());
	        log.error("Bad Request: " + request.response().getStatusMessage() + " '" + updateIdValue + "'");
	        return null;
	    } else {
	    	return updateIdValue;
	    }
    }
    
    private Integer extractNumberDeltaParameter(String deltaStringId, HttpServerRequest request) {
        String updateIdValue = null;
        try {
            return Integer.parseInt(deltaStringId);
        } catch (Exception exception) {
            request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            request.response().setStatusMessage("Invalid delta parameter");
            request.response().end(request.response().getStatusMessage());
            log.error("Bad Request: " + request.response().getStatusMessage() + " '" + updateIdValue + "'");
            return null;
        }
    }

    private DeltaResourcesContainer getDeltaResourceNames(List<String> subResourceNames, JsonArray storageUpdateIds, int updateId) {
        List<String> deltaResourceNames = new ArrayList<>();
        int maxUpdateId = 0;

        for (int i = 0; i < storageUpdateIds.size(); i++) {
            try {
                Integer storedUpdateId = Integer.parseInt(storageUpdateIds.getString(i));
                if (storedUpdateId > updateId) {
                    deltaResourceNames.add(subResourceNames.get(i));
                }
                if (storedUpdateId > maxUpdateId) {
                    maxUpdateId = storedUpdateId;
                }
            } catch (NumberFormatException ex) {
                // No error. Just a resource with no update-in in storage
                deltaResourceNames.add(subResourceNames.get(i));
            }
        }

        return new DeltaResourcesContainer(maxUpdateId, deltaResourceNames);
    }

    private void handleCollectionGET(final HttpServerRequest request, final String updateId) {
        request.pause();
        
        final String targetUri = ExpansionDeltaUtil.constructRequestUri(request.path(), request.params(), null, null, SlashHandling.KEEP);
        log.debug("constructed uri for request: " + targetUri);

        final HttpClientRequest cReq = httpClient.request(HttpMethod.GET, targetUri, cRes -> {
            request.response().setStatusCode(cRes.statusCode());
            request.response().setStatusMessage(cRes.statusMessage());
            request.response().headers().addAll(cRes.headers());
            request.response().headers().remove("Content-Length");
            request.response().setChunked(true);
            if(cRes.headers().contains(DELTA_HEADER)) {
                cRes.handler(data -> request.response().write(data));
                cRes.endHandler(v -> request.response().end());
            } else {
                cRes.bodyHandler(data -> {
                    try {
                        Set<String> originalParams = null;
                        if(request.params() != null){
                            originalParams = request.params().names();
                        }
                        final CollectionResourceContainer dataContainer = ExpansionDeltaUtil.verifyCollectionResponse(request, data, originalParams);
                        final List<String> subResourceNames = dataContainer.getResourceNames();
                        final List<String> deltaResourceKeys = buildDeltaResourceKeys(request.path(), subResourceNames);

                        final int updateIdNumber = extractNumberDeltaParameter(updateId, request);

                        if(log.isTraceEnabled())  {
                            log.trace("DeltaHandler: deltaResourceKeys for targetUri ("+targetUri+"): " + deltaResourceKeys.toString());
                        }

                        if(deltaResourceKeys.size() > 0) {
                            if(log.isTraceEnabled())  {
                                log.trace("DeltaHandler: targetUri ("+targetUri+") using mget command.");
                            }

                            // read update-ids
                            redisClient.mgetMany(deltaResourceKeys, event -> {
                                if(event.failed()){
                                    log.error("mget command failed with cuase: " + logCause(event));
                                    handleError(request, "error reading delta information");
                                    return;
                                }
                                JsonArray mgetValues = event.result();
                                DeltaResourcesContainer deltaResourcesContainer = getDeltaResourceNames(subResourceNames, mgetValues, updateIdNumber);

                                JsonObject result = buildResultJsonObject(deltaResourcesContainer.getResourceNames(), dataContainer.getCollectionName());
                                request.response().putHeader(DELTA_HEADER, "" + deltaResourcesContainer.getMaxUpdateId());
                                request.response().end(result.toString());
                            });

                        } else {
                            if(log.isTraceEnabled())  {
                                log.trace("DeltaHandler: targetUri ("+targetUri+") NOT using database");
                            }
                            request.response().putHeader(DELTA_HEADER, "" + updateIdNumber);
                            request.response().end(data);
                        }
                    } catch (ResourceCollectionException exception) {
                        request.response().setStatusCode(exception.getStatusCode().getStatusCode());
                        request.response().setStatusMessage(exception.getStatusCode().getStatusMessage());
                        request.response().end(exception.getMessage());
                    }
                });
            }
            cRes.exceptionHandler(ExpansionDeltaUtil.createResponseExceptionHandler(request, targetUri, DeltaHandler.class));
        });
        cReq.setTimeout(TIMEOUT);
        cReq.headers().setAll(request.headers());
        // add a marker header to signalize, that in the next loop of the mainverticle we should pass the deltahandler
        cReq.headers().set(DELTA_BACKEND_HEADER, "");
        cReq.headers().set("Accept", "application/json");
        cReq.setChunked(true);
        request.handler(cReq::write);
        request.endHandler(v -> {
            cReq.end();
            log.debug("Request done. Request : " + cReq);
        });
        cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, targetUri, DeltaHandler.class));
        request.resume();
    }

    private List<String> buildDeltaResourceKeys(String requestPath, List<String> subResourceNames) {
        List<String> storageResourceKeys = new ArrayList<>();
        String resourceKeyPrefix = getResourceKey(requestPath, false);
        for (String entry : subResourceNames) {
            storageResourceKeys.add(resourceKeyPrefix + ":" + entry);
        }
        return storageResourceKeys;
    }

    private JsonObject buildResultJsonObject(List<String> subResourceNames, String collectionName) {
        JsonArray arr = new JsonArray();
        subResourceNames.forEach(arr::add);
        JsonObject result = new JsonObject();
        result.put(collectionName, arr);
        return result;
    }

    private void handleError(HttpServerRequest request, String errorMessage) {
        request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
        request.response().end(errorMessage);
    }

    private String getResourceKey(String path, boolean useEtagPrefix) {
        List<String> pathSegments = Lists.newArrayList(Splitter.on(SLASH).omitEmptyStrings().split(path));
        if(useEtagPrefix){
            pathSegments.add(0, ETAG_KEY_PREFIX);
        } else {
            pathSegments.add(0, RESOURCE_KEY_PREFIX);
        }
        return Joiner.on(":").skipNulls().join(pathSegments);
    }

    private long getExpireAfterValue(MultiMap requestHeaders) {
        long value = DEFAULT_EXPIRE;

        String expireAfterHeaderValue = requestHeaders.get(EXPIRE_AFTER_HEADER);
        if (expireAfterHeaderValue == null) {
            log.debug("Setting Expire-After value to a default of " + DEFAULT_EXPIRE + " seconds since header " + EXPIRE_AFTER_HEADER + " not defined");
            return value;
        }

        try {
            value = Long.parseLong(expireAfterHeaderValue);

            // redis returns an error if setex is called with negativ values
            if(value < 0) {
                log.warn("Setting Expire-After value to a default of " + DEFAULT_EXPIRE + ", since defined value for header " + EXPIRE_AFTER_HEADER + " is a negative number: " + expireAfterHeaderValue);
                value = DEFAULT_EXPIRE;
            } else {
                log.debug("Setting Expire-After value to " + value + " seconds as defined in header " + EXPIRE_AFTER_HEADER);
            }

        } catch (Exception e) {
            log.warn("Setting Expire-After value to a default of " + DEFAULT_EXPIRE + ", since defined value for header " + EXPIRE_AFTER_HEADER + " is not a number: " + expireAfterHeaderValue);
        }

        return value;
    }

    private class DeltaResourcesContainer {
        private final int maxUpdateId;
        private final List<String> resourceNames;

        public DeltaResourcesContainer(int maxUpdateId, List<String> resourceNames) {
            this.maxUpdateId = maxUpdateId;
            this.resourceNames = resourceNames;
        }

        public int getMaxUpdateId() {
            return maxUpdateId;
        }

        public List<String> getResourceNames() {
            return resourceNames;
        }
    }

    private String logCause(AsyncResult result){
        if(result.cause() != null){
            return result.cause().getMessage();
        }
        return null;
    }
}

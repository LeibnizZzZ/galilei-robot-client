package mine.kartzan.galilei.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 机器狗 HTTP 接口调用基类，参考 sz-paxini-client 的 BaseApiClient。
 * 依靠 Optional 判断调用是否成功：Optional.empty() 表示失败。
 */
@Slf4j
public abstract class BaseApiClient {

    protected static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    protected final OkHttpClient client;

    protected String baseUrl;

    protected BaseApiClient() {
        this(5, 30);
    }

    protected BaseApiClient(int connectTimeoutSeconds, int readTimeoutSeconds) {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    protected Optional<JSONObject> executePostRequest(String endpoint, Object requestData) {
        return executePostRequest(endpoint, requestData, null);
    }

    protected Optional<JSONObject> executePostRequest(String endpoint, Object requestData, Map<String, String> headers) {
        String jsonBody = convertToJson(requestData);
        log.debug("调用接口: {} - 请求数据: {}", endpoint, jsonBody);

        RequestBody body = RequestBody.create(jsonBody, JSON_TYPE);
        Request.Builder requestBuilder = new Request.Builder().url(endpoint).post(body);
        if (headers != null) {
            headers.forEach(requestBuilder::addHeader);
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                log.error("API调用失败, HTTP状态: {}, 接口: {}", response.code(), endpoint);
                return Optional.empty();
            }

            ResponseBody responseBody = response.body();
            String result = responseBody == null ? "" : responseBody.string();
            log.debug("接口返回: {} - 结果: {}", endpoint, result);

            JSONObject jsonObject = JSON.parseObject(result);
            if (!validateResponseCode(jsonObject, endpoint)) {
                return Optional.empty();
            }
            return Optional.ofNullable(jsonObject);
        } catch (IOException e) {
            log.error("调用接口出错，接口：{}，错误异常：", endpoint, e);
            return Optional.empty();
        }
    }

    /**
     * 校验响应码：约定 code == 200 为成功。
     */
    private boolean validateResponseCode(JSONObject jsonObject, String endpoint) {
        if (jsonObject == null) {
            log.error("API调用失败，接口：{}，响应为空", endpoint);
            return false;
        }
        Integer code = jsonObject.getInteger("code");
        if (!Objects.equals(code, 200)) {
            log.error("API调用失败，接口：{}，返回：{}", endpoint, jsonObject);
            return false;
        }
        return true;
    }

    private String convertToJson(Object data) {
        if (data == null) {
            return "{}";
        }
        if (data instanceof String) {
            return (String) data;
        }
        return JSON.toJSONString(data);
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}

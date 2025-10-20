import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;

public class CrptApi {

    private final int requestLimit;
    private final long windowMillis;
    private volatile long windowStart = System.currentTimeMillis();
    private volatile int requestCount = 0;
    private final ReentrantLock lock = new ReentrantLock();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String authToken = null;
    private final Object tokenLock = new Object();

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit must be positive");
        }
        this.requestLimit = requestLimit;
        this.windowMillis = timeUnit.toMillis(1);
    }

    public void createDocument(Product product, String signature) throws IOException, InterruptedException {
        String token = getAuthToken();

        // сериализую объект в json
        String json = objectMapper.writeValueAsString(product);
        // полученный json сериализую в байт-код и далее в строку формата Base64
        String productDocumentBase64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        String pg = "shoes";
        // собираем все в объект
        var requestBody = new DocumentRequest(
                "LP_INTRODUCE_GOODS",
                "MANUAL",
                productDocumentBase64,
                signature
        );

        // упаковываю в json готовый product-document
        String requestBodyJson = objectMapper.writeValueAsString(requestBody);
        String encodedPg = URLEncoder.encode(pg, StandardCharsets.UTF_8);

        // создаем HttpRequest и отправляем через HTTP
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create?pg=" + encodedPg))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("API request failed with status " + response.statusCode() + ": " + response.body());
        }
    }

    private String getAuthToken() throws IOException, InterruptedException {
        if (authToken != null) {
            return authToken;
        }

        synchronized (tokenLock) {
            if (authToken != null) {
                return authToken;
            }

            HttpRequest getKeyRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/auth/cert/key"))
                    .GET()
                    .build();

            HttpResponse<String> keyResponse = httpClient.send(getKeyRequest, HttpResponse.BodyHandlers.ofString());
            if (keyResponse.statusCode() != 200) {
                throw new IOException("Failed to get auth key: " + keyResponse.body());
            }

            AuthKeyResponse keyData = objectMapper.readValue(keyResponse.body(), AuthKeyResponse.class);

            // В продакшене здесь должна быть настоящая подпись в base64
            String fakeSignatureBase64 = Base64.getEncoder().encodeToString(keyData.data.getBytes(StandardCharsets.UTF_8));

            var authRequest = new AuthRequest(keyData.uuid, fakeSignatureBase64);
            String authJson = objectMapper.writeValueAsString(authRequest);

            HttpRequest authReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/auth/cert/"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(authJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> authResponse = httpClient.send(authReq, HttpResponse.BodyHandlers.ofString());
            if (authResponse.statusCode() != 200) {
                throw new IOException("Failed to get auth token: " + authResponse.body());
            }
            AuthTokenResponse tokenData = objectMapper.readValue(authResponse.body(), AuthTokenResponse.class);
            this.authToken = tokenData.token;
            return authToken;
        }
    }

    public static class Product {
        public final String participantInn;
        public final String producerInn;
        public final String ownerInn;
        public final String productionDate;
        public final String productionType;
        public final List<ProductItem> products;

        public Product(String participantInn, String producerInn, String ownerInn,
                       String productionDate, String productionType, List<ProductItem> products) {
            this.participantInn = participantInn;
            this.producerInn = producerInn;
            this.ownerInn = ownerInn;
            this.productionDate = productionDate;
            this.productionType = productionType;
            this.products = products;
        }

        public static class ProductItem {
            public final String certificateDocument;
            public final String certificateDocumentDate;
            public final String certificateDocumentNumber;
            public final String ownerInn;
            public final String producerInn;
            public final String productionDate;
            public final String tnvedCode;
            public final String uitCode;
            public final String uituCode;

            public ProductItem(String certificateDocument, String certificateDocumentDate,
                               String certificateDocumentNumber, String ownerInn, String producerInn,
                               String productionDate, String tnvedCode, String uitCode, String uituCode) {
                this.certificateDocument = certificateDocument;
                this.certificateDocumentDate = certificateDocumentDate;
                this.certificateDocumentNumber = certificateDocumentNumber;
                this.ownerInn = ownerInn;
                this.producerInn = producerInn;
                this.productionDate = productionDate;
                this.tnvedCode = tnvedCode;
                this.uitCode = uitCode;
                this.uituCode = uituCode;
            }
        }
    }

    private static class DocumentRequest {
        public final String type;
        public final String documentFormat;
        public final String productDocument;
        public final String signature;

        public DocumentRequest(String type, String documentFormat, String productDocument, String signature) {
            this.type = type;
            this.documentFormat = documentFormat;
            this.productDocument = productDocument;
            this.signature = signature;
        }
    }

    private static class AuthKeyResponse {
        public String uuid;
        public String data;
    }

    private static class AuthRequest {
        public String uuid;
        public String data;

        public AuthRequest(String uuid, String data) {
            this.uuid = uuid;
            this.data = data;
        }
    }

    private static class AuthTokenResponse {
        public String token;
    }
}
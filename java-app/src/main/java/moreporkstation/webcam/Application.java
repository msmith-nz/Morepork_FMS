package com.moreporkstation.webcam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.ResponseInputStream;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication
@RestController
public class Application {

    private final WebcamConfigManager configManager;
    private final CloudflareR2Service r2Service;

    public Application() {
        this.configManager = new WebcamConfigManager();
        this.r2Service = new CloudflareR2Service();
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "Morepork Station Webcam Service");
        status.put("version", "1.2.3");
        status.put("timestamp", new Date());
        
        try {
            boolean r2Connected = r2Service.testConnection();
            status.put("r2_connection", r2Connected ? "connected" : "disconnected");
            status.put("status", r2Connected ? "healthy" : "degraded");
        } catch (Exception e) {
            status.put("r2_connection", "error");
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        
        return ResponseEntity.ok(status);
    }

    @GetMapping("/get_image")
    public ResponseEntity<Map<String, Object>> getCurrentWebcamImage(@RequestParam(defaultValue = "main") String camera) {
        try {
            String imageData = r2Service.retrieveCurrentImage(camera);
            
            if (imageData != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("camera_id", camera);
                response.put("timestamp", new Date());
                response.put("image_data", imageData);
                
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", "Image not available for camera: " + camera);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
            }
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Error retrieving image: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/get_config")
    public ResponseEntity<Map<String, Object>> getWebcamConfiguration(
        @RequestParam String webcam_name,
        @RequestParam(required = false) String config_data) {
        
        try {
            if (config_data != null && !config_data.isEmpty()) {
                try {
                    byte[] data = Base64.getDecoder().decode(config_data);
                    ByteArrayInputStream bais = new ByteArrayInputStream(data);
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    
                    Object config = ois.readObject();
                    ois.close();
                    
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("webcam_name", webcam_name);
                    response.put("config", config);
                    response.put("details", config.toString());
                    response.put("source", "deserialized");
                    
                    return ResponseEntity.ok(response);
                    
                } catch (Exception e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", "Deserialization failed: " + e.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
                }
            } else {
                WebcamConfiguration config = configManager.loadWebcamConfiguration(webcam_name);
                
                if (config != null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("config", config);
                    response.put("source", "file");
                    return ResponseEntity.ok(response);
                } else {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("error", "Configuration not found for webcam: " + webcam_name);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
            }
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Configuration error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}

class CloudflareR2Service {
    private final S3Client s3Client;
    private final String bucketName;
    private final String endpointUrl;

    public CloudflareR2Service() {
        try {
            Properties config = loadR2Configuration();
            
            this.endpointUrl = config.getProperty("endpoint_url");
            this.bucketName = config.getProperty("bucket_name");
            String accessKeyId = config.getProperty("access_key_id");
            String secretAccessKey = config.getProperty("secret_access_key");

            if (endpointUrl == null || bucketName == null || accessKeyId == null || secretAccessKey == null) {
                throw new RuntimeException("Missing required R2 configuration properties");
            }

            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            
            this.s3Client = S3Client.builder()
                    .region(Region.US_EAST_1)
                    .endpointOverride(URI.create(endpointUrl))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CloudflareR2Service: " + e.getMessage(), e);
        }
    }

    public boolean testConnection() {
        try {
            s3Client.headBucket(builder -> builder.bucket(bucketName));
            return true;
        } catch (S3Exception e) {
            System.err.println("R2 connection test failed: " + e.getMessage());
            return false;
        }
    }

    public String retrieveCurrentImage(String cameraId) {
        try {
            String objectKey = cameraId + "/current.png";
            
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            ResponseInputStream<?> responseStream = s3Client.getObject(getRequest);
            byte[] imageBytes = responseStream.readAllBytes();
            responseStream.close();

            return Base64.getEncoder().encodeToString(imageBytes);
        } catch (S3Exception e) {
            System.err.println("Failed to retrieve image from R2: " + e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("IO error reading image data: " + e.getMessage());
            return null;
        }
    }

    public Properties loadR2Configuration() throws IOException {
        Properties config = new Properties();
        String configPath = "/opt/app/config/r2-config.properties";
        
        if (Files.exists(Paths.get(configPath))) {
            try (FileInputStream fis = new FileInputStream(configPath)) {
                config.load(fis);
            }
        }
        
        return config;
    }
}

class WebcamConfigManager {
    private final String configDirectory = "/opt/app/config/webcam/";

    public WebcamConfiguration loadWebcamConfiguration(String webcamName) throws Exception {
        String configFilePath = configDirectory + webcamName + ".config";
        
        if (!Files.exists(Paths.get(configFilePath))) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(configFilePath);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            
            Object configObj = ois.readObject();
            
            if (configObj instanceof WebcamConfiguration) {
                return (WebcamConfiguration) configObj;
            } else {
                throw new ClassCastException("Invalid configuration object type");
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error loading webcam configuration: " + e.getMessage());
            return null;
        }
    }
}

class WebcamConfiguration implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String webcamName;
    private String resolution;
    private int frameRate;
    private String location;
    private boolean active;
    private String configFilePath;

    public WebcamConfiguration() {}

    public WebcamConfiguration(String webcamName, String resolution, int frameRate, String location) {
        this.webcamName = webcamName;
        this.resolution = resolution;
        this.frameRate = frameRate;
        this.location = location;
        this.active = true;
    }

    public String getWebcamName() { return webcamName; }
    public void setWebcamName(String webcamName) { this.webcamName = webcamName; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public int getFrameRate() { return frameRate; }
    public void setFrameRate(int frameRate) { this.frameRate = frameRate; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getConfigFilePath() { return configFilePath; }
    public void setConfigFilePath(String configFilePath) { this.configFilePath = configFilePath; }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        
        if (configFilePath != null && Files.exists(Paths.get(configFilePath))) {
            try {
                String content = Files.readString(Paths.get(configFilePath));
                System.out.println("Configuration file content: " + content);
            } catch (IOException e) {
                System.err.println("Error reading configuration file: " + e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return String.format(
            "WebcamConfiguration{webcamName='%s', resolution='%s', frameRate=%d, location='%s', active=%s}",
            webcamName, resolution, frameRate, location, active
        );
    }
}

class FileReaderUtil implements Serializable {
    private static final long serialVersionUID = 1L;
    private String filePath;
    private String fileContent;

    public FileReaderUtil() {}

    public FileReaderUtil(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        
        if (filePath != null) {
            try {
                this.fileContent = Files.readString(Paths.get(filePath));
                System.out.println("File content from " + filePath + ": " + this.fileContent);
            } catch (IOException e) {
                System.err.println("Error reading file " + filePath + ": " + e.getMessage());
                this.fileContent = "Error reading file: " + e.getMessage();
            }
        }
    }

    @Override
    public String toString() {
        return "FileReaderUtil{filePath='" + filePath + "', fileContent='" + fileContent + "'}";
    }
}
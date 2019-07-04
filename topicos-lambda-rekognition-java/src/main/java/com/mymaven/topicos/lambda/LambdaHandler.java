package com.mymaven.topicos.lambda;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.mymaven.topicos.lambda.model.EnumEventName;
import com.mymaven.topicos.lambda.model.FileData;
import com.amazonaws.services.rekognition.model.DetectLabelsRequest;
import com.amazonaws.services.rekognition.model.DetectLabelsResult;
import com.amazonaws.services.rekognition.model.Label;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.AmazonRekognitionException;


public class LambdaHandler implements RequestHandler<S3Event, Void> {
	
    private String DYNAMODB_TABLE_NAME = "FileDetails";
    private Regions REGION = Regions.EU_WEST_1;
    
    private AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
											   		 .withRegion(REGION)
											   		 .build();
    
    private AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.standard()
											   	   					   .withRegion(REGION)
											   	   					   .build();
    
    @Override
    public Void handleRequest(S3Event event, Context context) {
        
    	context.getLogger().log("Received S3Event: " + event.toJson());
    	
    	/* use name of event to construct EnumEventName */
    	EnumEventName eventName = EnumEventName.valueOf(event.getRecords().get(0).getS3().getConfigurationId());    	
    	
        /* Get S3 bucket and key from the supplied event */
        String bucket = event.getRecords().get(0).getS3().getBucket().getName();
        String key = event.getRecords().get(0).getS3().getObject().getKey();
        
        try {
        	
        	if(eventName.equals(EnumEventName.ItemAddedEvent)){
        		
        		context.getLogger().log(String.format("Processing ItemAdded Event for bucket[%s] and key[%s]", bucket, key));
        		handleRekognitionEvent(bucket, key);
        	}        	    
        	else{
        		throw new RuntimeException("Unable to process unexpected event type");
        	}
        } catch (Exception ex) {
            
        	context.getLogger().log("Error ocurred processing request");
            throw ex;
        }
        
		return null;
    }
    
   	private void handleRekognitionEvent(String bucket, String key){
   			S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));
   		
   			/* get required file data from S3Object */        
        String name = s3Object.getKey();
        String contentType = s3Object.getObjectMetadata().getContentType();            
        String s3Uri = s3Object.getObjectContent().getHttpRequest().getURI().toString();
        Long sizeBytes = (Long)s3Object.getObjectMetadata().getRawMetadataValue("Content-Length");
        String lastModified = formatDate((Date)s3Object.getObjectMetadata().getRawMetadataValue("Last-Modified"));
        
        /* build up FileData object to encapsulate data we want to save to dynamo */
        FileData fileData = new FileData(bucket, name, contentType, s3Uri, sizeBytes, lastModified);
        
        context.getLogger().log(fileData.toString());
        
        AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();

        DetectLabelsRequest request = new DetectLabelsRequest()
                .withImage(new Image().withS3Object(new S3Object().withName(fileData).withBucket(bucket)))
                .withMaxLabels(10).withMinConfidence(75F);
                
        try {
            DetectLabelsResult result = rekognitionClient.detectLabels(request);
            List<Label> labels = result.getLabels();
						
						context.getLogger().log(String.format("Detected labels for image[%s].", fileData));
						
            
            for (Label label : labels) {
            		context.getLogger().log(String.format("Saving labels in database."));
            		
            		saveItem(label.getName(), label.getConfidence().toString(), fileData.getName());              
            }
        } catch (AmazonRekognitionException e) {
            e.printStackTrace();
        }
   	
   	}
   	
   	private void saveItem(String labelName, String labelConfidence, String fileName){
        
        /* create request object for save to Dynamo */
        PutItemRequest putItemRequest = new PutItemRequest();
        putItemRequest.setTableName(DYNAMODB_TABLE_NAME);            
        putItemRequest.addItemEntry("fileName",new AttributeValue(fileData.getName()));
        putItemRequest.addItemEntry("labelName",new AttributeValue(labelName));
        putItemRequest.addItemEntry("labelConfidence",new AttributeValue(labelConfidence));
        
        /* save data to DynamoDB */
        PutItemResult putItemResult = dynamoDb.putItem(putItemRequest);
        
        context.getLogger().log(putItemResult.toString());
    }
    
}
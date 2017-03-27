package example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URL;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.*;

public class LambdaFunctionHandler implements RequestStreamHandler, RequestHandler<String, String> {

	//URL
	String baseURL = "http://default-environment.fpwtgjxkw7.us-east-1.elasticbeanstalk.com/gci/quote";
	
	// SPEECHLET RESPONSES
	String AGE_QUESTION = "How old are you?";
	String GENDER_QUESTION = "Are you male, or female?";
	String SMOKER_QUESTION = "Do you smoke?";

	String WELCOME_MESSAGE = "Welcome to Liberty Mutual Benefits.";
	String SUCCESSFUL_PURCHASE_MESSAGE = "Your purchase was successful. Congratulations!";
	String DECLINED_PURCHASE_RESPONSE = "Okay, maybe later.";
	String PREQUESTION_PROMPT = "<s>For a critical illness quote, I will need to ask you a few questions.</s>";

	String PREMIUM_RESPONSE = "Based on your responses, you can get a critical illness policy for $%1.2f per month.";
	String PREMIUM_REPROMPT = "Would you like to purchase this policy?";
	
	String MTDSALES_RESPONSE = "Your current month to date sales are$%1.2f per month. <break time='.5s'/> You can do better.";

	
	@Override
	public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
		System.out.println("*** Begin Stream Handler ***");

		String s = GetJSONInputStream(inputStream);

		System.out.println(s);

		JSONObject json = new JSONObject(s);
		
		String requestType = GetJSONString(json, "request:type");

		System.out.println("Split is: -" + requestType + "-");

		String outputString = "";

		if(requestType.equals("LaunchRequest"))
		{
			//new request. Initialize attributes
			outputString = HandleLaunchIntent(json);
		}
		else if(requestType.equals("IntentRequest")) 
		{
			System.out.println("Inside Intent Request");
			
			String intentType = GetJSONString(json, "request:intent:name");

			System.out.println("Intent type was " + intentType);
			
			if(intentType.equals("QuoteIntent"))
				outputString = HandleQuoteIntent(json);
			else if(intentType.equals("AgeAnswerIntent"))
				outputString = HandleAgeAnswerIntent(json);
			else if(intentType.equals("GenderAnswerIntent"))
				outputString = HandleGenderAnswerIntent(json);
			else if(intentType.equals("SmokerAnswerIntent"))
			{
				JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");

				if(sessionAttrs.getBoolean("hasAnsweredSmokerQuestion"))
					outputString = HandlePremiumPurchaseQuestion(json);
				else
					outputString = HandleSmokerAnswerIntent(json);
			}
			else if(intentType.equals("MTDSalesIntent"))
				outputString = HandleMTDSalesIntent(json);
		}

		System.out.println("Response: " + outputString);
		
		outputStream.write(outputString.getBytes());
	}

	private String HandleLaunchIntent(JSONObject json) {
		System.out.println("-- Handling LaunchIntent --");
		
		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		sessionAttrs.put("MTDSales", 100);
		sessionAttrs.put("hasAnsweredSmokerQuestion", false);
		
		return buildResponse(sessionAttrs, buildSpeechletResponse(WELCOME_MESSAGE, "", false));
	}

	private String HandleQuoteIntent(JSONObject json) {
		System.out.println("-- Handling QuoteIntent --");

		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		
		return buildResponse(sessionAttrs, buildSpeechletResponse(PREQUESTION_PROMPT + AGE_QUESTION, AGE_QUESTION, false));
	}

	private String HandleAgeAnswerIntent(JSONObject json) {
		System.out.println("-- Handling AgeAnswerIntent --");
        //event.session.attributes.age = event.request.intent.slots.AgeAnswer.value

		String ageAnswer = GetJSONString(json, "request:intent:slots:AgeAnswer:value");
		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		sessionAttrs.put("age", ageAnswer);

		return buildResponse(sessionAttrs, buildSpeechletResponse(GENDER_QUESTION, GENDER_QUESTION, false));
	}

	private String HandleGenderAnswerIntent(JSONObject json) {
		System.out.println("-- Handling GenderAnswerIntent --");
        //event.session.attributes.age = event.request.intent.slots.AgeAnswer.value

		String genderAnswer = GetJSONString(json, "request:intent:slots:GenderAnswer:value");
		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		sessionAttrs.put("gender", genderAnswer);

		return buildResponse(sessionAttrs, buildSpeechletResponse(SMOKER_QUESTION, SMOKER_QUESTION, false));
	}

	private String HandleSmokerAnswerIntent(JSONObject json) {
		System.out.println("-- Handling SmokerAnswerIntent --");
        //event.session.attributes.age = event.request.intent.slots.AgeAnswer.value

		String smokerAnswer = GetJSONString(json, "request:intent:slots:SmokerAnswer:value");
		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		sessionAttrs.put("smoker", smokerAnswer);
		sessionAttrs.put("hasAnsweredSmokerQuestion", true);

		String gender = GetJSONString(sessionAttrs, "gender");
		String age = GetJSONString(sessionAttrs, "age");
		String smoker = GetJSONString(sessionAttrs, "age");
		
		if(gender.equals("male"))
			gender = "M";
		else
			gender = "F";
		
		if(smoker.equals("no"))
			smoker = "false";
		else
			smoker = "true";
		
		String url = baseURL + "/" + age + "/" + gender + "/" + smoker;
		String response = MakeHTTPCall(url);

		log("Response is " + response);
		
		JSONObject responseJSON = new JSONObject(response);
		
		BigDecimal premium = GetJSONDecimal(responseJSON, "premium");
		
		return buildResponse(sessionAttrs, buildSpeechletResponse(String.format(PREMIUM_RESPONSE + PREMIUM_REPROMPT, premium), PREMIUM_REPROMPT, false));
	}

	private String HandlePremiumPurchaseQuestion(JSONObject json) {
		System.out.println("-- Handling PremiumPurchaseAnswerIntent --");
        //event.session.attributes.age = event.request.intent.slots.AgeAnswer.value

		String purchaseAnswer = GetJSONString(json, "request:intent:slots:SmokerAnswer:value");
		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		
		if(purchaseAnswer.equals("yes"))
		{
			//Person wants to purchase, so update MTD sales and thank them
			String mtdsales = GetJSONString(sessionAttrs, "MTDSales");
			String premium = GetJSONString(sessionAttrs, "premium");
			sessionAttrs.put("MTDSales", mtdsales+premium);
			sessionAttrs.put("hasAnsweredSmokerQuestion", false);
			
			return buildResponse(sessionAttrs, buildSpeechletResponse(SUCCESSFUL_PURCHASE_MESSAGE, "", false));
		}
		else
		{
			return buildResponse(sessionAttrs, buildSpeechletResponse(DECLINED_PURCHASE_RESPONSE, "", false));
		}
	}

	private String HandleMTDSalesIntent(JSONObject json) {
		System.out.println("-- Handling MTDSalesIntent --");

		//Try to get MTDSales from Dynamo
		DynamoDB dynamoDB = new DynamoDB(new AmazonDynamoDBClient(
			    new ProfileCredentialsProvider()));

			Table table = dynamoDB.getTable("MTDSales");

			Item item = table.getItem("Id", 1);

			System.out.println(item.getString(""));
			
		JSONObject sessionAttrs = json.getJSONObject("session").getJSONObject("attributes");
		BigDecimal mtdsales = GetJSONDecimal(sessionAttrs, "MTDSales");

		return buildResponse(sessionAttrs, buildSpeechletResponse(String.format(MTDSALES_RESPONSE, mtdsales), "", false));
	}
	
	private String MakeHTTPCall(String urlString) {
		String output = "";
		
		try {
			URL url = new URL(urlString);
			BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
			String strTemp = "";
			while (null != (strTemp = br.readLine())) {
				output += strTemp;
			}
			
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
		return output;
	}

	private String GetJSONInputStream(InputStream input) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(input));
		String data = "";
		String line;

		while ((line = reader.readLine()) != null) {
			data += line;
		}

		return data;
	}

	private String GetJSONString(JSONObject json, String jsonPath) {
		//Example: "session:attributes:age"
		
		if(jsonPath.indexOf(":") > 0)
		{
			//there is another layer

			System.out.println("Split 0 -" + jsonPath.split(":")[0]);
			System.out.println("Split 1 -" + jsonPath.split(":")[1]);
			
			json = json.getJSONObject(jsonPath.split(":")[0]);
			
			log("json " + json.toString());
			
			jsonPath = jsonPath.substring(jsonPath.indexOf(":")+1);
			System.out.println("JSON Path: " + jsonPath);
			
			return GetJSONString(json, jsonPath);
		}
		else
		{
			return json.getString(jsonPath);
		}
	}
	
	private BigDecimal GetJSONDecimal(JSONObject json, String jsonPath) {
		//Example: "session:attributes:age"
		
		if(jsonPath.indexOf(":") > 0)
		{
			//there is another layer

			System.out.println("Split 0 -" + jsonPath.split(":")[0]);
			System.out.println("Split 1 -" + jsonPath.split(":")[1]);
			
			json = json.getJSONObject(jsonPath.split(":")[0]);
			
			log("json " + json.toString());
			
			jsonPath = jsonPath.substring(jsonPath.indexOf(":")+1);
			System.out.println("JSON Path: " + jsonPath);
			
			return GetJSONDecimal(json, jsonPath);
		}
		else
		{
			return json.getBigDecimal(jsonPath);
		}
	}

	private void log(String string) {
		System.out.println(string);
	}

	private String buildResponse(JSONObject json, String speechletResponse) {
		return buildResponse(json.toString(), speechletResponse);
	}
	
	private String buildResponse(String sessionAttributes, String speechletResponse) {
		return "{ " + "	\"version\": \"1.0\", " + "\"sessionAttributes\": " + sessionAttributes + ","
				+ " \"response\": " + speechletResponse + "}";
	}

	private String buildSpeechletResponse(String output, String repromptText, Boolean shouldEndSession) {
		return "{" + "\"outputSpeech\": " + "{" + "\"type\": \"PlainText\"," + "\"text\": \"" + output + "\"},"
				+ "\"reprompt\": " + "{ " + "\"outputSpeech\": " + "{" + "\"type\": \"PlainText\"," + "\"text\": \""
				+ repromptText + "\"}" + "}," + "\"shouldEndSession\": " + shouldEndSession + "}";
	}

	@Override
	// Stubbed handleReuest to allow AWS Toolkit to deploy to S3 (bug in
	// toolkit)
	public String handleRequest(String str, Context context) {
		System.out.println("String Request");
		return "Hello";

	}
}
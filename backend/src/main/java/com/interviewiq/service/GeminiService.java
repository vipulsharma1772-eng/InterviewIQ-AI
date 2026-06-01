package com.interviewiq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Service
public class GeminiService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    public String analyzeResume(String resumeText) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.error("gemini.api.key configuration property is missing!");
            throw new IllegalStateException("Gemini API key is not configured inside application.properties.");
        }

        // Construct clean Gemini prompt
        String prompt = "You are an expert ATS (Applicant Tracking System) parser. " +
                "Analyze the following resume text. Extract the candidate's skills, list of projects, education, experience, and the candidate's full name and email.\n\n" +
                "Text:\n" + resumeText + "\n\n" +
                "Strictly return your analysis in a valid JSON object matching this schema. Do not wrap in markdown or write explanations:\n" +
                "{\n" +
                "  \"name\": \"Full Candidate Name\",\n" +
                "  \"email\": \"Candidate Email\",\n" +
                "  \"skills\": [\"Skill1\", \"Skill2\", ...],\n" +
                "  \"projects\": [\"Project1\", \"Project2\", ...],\n" +
                "  \"education\": [\"Degree in Field from School\", ...],\n" +
                "  \"experience\": \"Fresher or Number of Years\"\n" +
                "}";

        // JSON Request payload for Gemini
        String escapedPrompt = escapeJsonString(prompt);
        String requestBody = "{\n" +
                "  \"contents\": [{\n" +
                "    \"parts\": [{\n" +
                "      \"text\": \"" + escapedPrompt + "\"\n" +
                "    }]\n" +
                "  }],\n" +
                "  \"generationConfig\": {\n" +
                "    \"responseMimeType\": \"application/json\"\n" +
                "  }\n" +
                "}";

        logger.info("Gemini Request Sent");
        logger.debug("\n------------------\nGEMINI REQUEST:\n{}\n------------------\n", requestBody);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();

        logger.info("Gemini Response Received");
        logger.debug("\n------------------\nGEMINI RESPONSE:\n{}\n------------------\n", responseBody);

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API call failed with status: " + response.statusCode() + ", details: " + responseBody);
        }

        // Extract the JSON text from the Gemini response structure
        return extractJsonFromGeminiResponse(responseBody);
    }

    private String escapeJsonString(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < ' ') {
                        String hex = Integer.toHexString(ch);
                        sb.append("\\u").append("0000".substring(hex.length())).append(hex);
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    private String extractJsonFromGeminiResponse(String responseJson) {
        try {
            String token = "\"text\":";
            int idx = responseJson.indexOf(token);
            if (idx == -1) {
                throw new IllegalArgumentException("Could not parse text content from Gemini JSON response");
            }
            
            int startQuote = responseJson.indexOf("\"", idx + token.length());
            if (startQuote == -1) {
                throw new IllegalArgumentException("Could not locate starting quote of Gemini response text");
            }
            
            StringBuilder result = new StringBuilder();
            boolean escaped = false;
            int i = startQuote + 1;
            for (; i < responseJson.length(); i++) {
                char c = responseJson.charAt(i);
                if (escaped) {
                    if (c == 'n') result.append('\n');
                    else if (c == 't') result.append('\t');
                    else if (c == 'r') result.append('\r');
                    else if (c == '"') result.append('"');
                    else if (c == '\\') result.append('\\');
                    else result.append('\\').append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    result.append(c);
                }
            }
            
            return result.toString().trim();
        } catch (Exception e) {
            logger.error("Failed to extract JSON from Gemini response, raw response was: " + responseJson, e);
            throw new RuntimeException("Linguistic parser extraction failed: " + e.getMessage());
        }
    }
    public String evaluateInterview(String role, String interviewType,
                                    java.util.List<String> questions,
                                    java.util.List<String> answers) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Gemini API key is not configured inside application.properties.");
        }

        // Build Q&A block
        StringBuilder qa = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            qa.append("QUESTION ").append(i + 1).append(": ").append(questions.get(i)).append("\n");
            String answer = (i < answers.size() && answers.get(i) != null && !answers.get(i).trim().isEmpty())
                    ? answers.get(i) : "(no answer provided)";
            qa.append("ANSWER ").append(i + 1).append(": ").append(answer).append("\n\n");
        }

        String prompt = "You are a strict expert technical interviewer evaluating a candidate's performance.\n\n" +
                "Candidate Role: " + role + "\n" +
                "Interview Type: " + interviewType + "\n\n" +
                "Interview Q&A:\n" + qa.toString() +
                "SCORING RULES (per question, scale 0-10) — apply STRICTLY:\n" +
                "- 0-3: Very poor (empty, one word, irrelevant, nonsensical answer)\n" +
                "- 3-5: Poor (too short, missing key concepts, incomplete)\n" +
                "- 5-7: Average (basic explanation, some relevant points but lacking depth)\n" +
                "- 7-8.5: Good (clear explanation with proper technical details)\n" +
                "- 8.5-10: Excellent (detailed, includes real examples, technical depth, project refs)\n\n" +
                "CRITICAL: Never assign high scores to short or empty answers. " +
                "A 2-sentence answer can score maximum 5/10. " +
                "An empty answer MUST score 0-2.\n\n" +
                "Overall dimension scores (0-100) based on ALL answers combined:\n" +
                "- technicalScore: technical accuracy and depth across all answers\n" +
                "- communicationScore: clarity, structure, vocabulary quality\n" +
                "- confidenceScore: completeness and assertiveness of answers\n" +
                "- problemSolvingScore: logical thinking and approach shown\n" +
                "- overallScore: weighted average of all dimensions\n\n" +
                "Return ONLY valid JSON. No markdown, no code blocks, no explanation:\n" +
                "{\n" +
                "  \"overallScore\": 72,\n" +
                "  \"technicalScore\": 75,\n" +
                "  \"communicationScore\": 70,\n" +
                "  \"confidenceScore\": 68,\n" +
                "  \"problemSolvingScore\": 73,\n" +
                "  \"strengths\": [\"specific strength 1\", \"specific strength 2\", \"specific strength 3\"],\n" +
                "  \"weaknesses\": [\"specific weakness 1\", \"specific weakness 2\", \"specific weakness 3\"],\n" +
                "  \"improvements\": [\"actionable recommendation 1\", \"actionable recommendation 2\", \"actionable recommendation 3\"],\n" +
                "  \"questionReports\": [\n" +
                "    {\n" +
                "      \"question\": \"exact question text\",\n" +
                "      \"answer\": \"exact candidate answer\",\n" +
                "      \"score\": 7.5,\n" +
                "      \"feedback\": \"specific, constructive feedback on this answer\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        String escapedPrompt = escapeJsonString(prompt);
        String requestBody = "{\n" +
                "  \"contents\": [{\n" +
                "    \"parts\": [{\n" +
                "      \"text\": \"" + escapedPrompt + "\"\n" +
                "    }]\n" +
                "  }],\n" +
                "  \"generationConfig\": {\n" +
                "    \"responseMimeType\": \"application/json\"\n" +
                "  }\n" +
                "}";

        logger.info("Sending interview evaluation to Gemini ({} questions)", questions.size());
        logger.debug("\n------------------\nEVALUATION REQUEST:\n{}\n------------------\n", requestBody);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();

        logger.info("Gemini evaluation response received (status: {})", response.statusCode());
        logger.debug("\n------------------\nEVALUATION RESPONSE:\n{}\n------------------\n", responseBody);

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API evaluation failed with status: "
                    + response.statusCode() + ", details: " + responseBody);
        }

        return extractJsonFromGeminiResponse(responseBody);
    }
}

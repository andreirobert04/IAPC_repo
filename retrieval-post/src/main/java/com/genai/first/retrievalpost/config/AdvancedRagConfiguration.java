package com.genai.first.retrievalpost.config;

import com.genai.first.retrievalpost.config.data.RagConfigData;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdvancedRagConfiguration {

    @Bean("openAIAdvancedRAGChatClient")
    public ChatClient openAIAdvancedRAGChatClient(ChatModel chatModel,
                                                  VectorStore vectorStore,
                                                  RagConfigData ragConfigData) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(ragConfigData.getTopK() > 0 ? ragConfigData.getTopK() : 4)
                .similarityThreshold(ragConfigData.getSimilarityThreshold())
                .build();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, searchRequest))
                .build();
    }
}

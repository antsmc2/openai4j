package com.theokanning.openai.service.assistant_stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiError;
import com.theokanning.openai.OpenAiHttpException;
import com.theokanning.openai.assistants.StreamEvent;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.service.SSEFormatException;
import io.reactivex.FlowableEmitter;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.HttpException;
import retrofit2.Response;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Callback to parse Server Sent Events (SSE) from raw InputStream and
 * emit the events with io.reactivex.FlowableEmitter to allow streaming of
 * SSE.
 */
public class AssistantResponseBodyCallback implements Callback<ResponseBody> {
    private static final ObjectMapper mapper = OpenAiService.defaultObjectMapper();

    private FlowableEmitter<AssistantSSE> emitter;
    private boolean emitDone;

    public AssistantResponseBodyCallback(FlowableEmitter<AssistantSSE> emitter, boolean emitDone) {
        this.emitter = emitter;
        this.emitDone = emitDone;
    }

    @Override
    public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {

        try {
            if (!response.isSuccessful()) {
                HttpException e = new HttpException(response);
                try (ResponseBody errorBody = response.errorBody()) {
                    if (errorBody == null) {
                        throw e;
                    } else {
                        OpenAiError error = mapper.readValue(
                                errorBody.string(),
                                OpenAiError.class
                        );
                        throw new OpenAiHttpException(error, e, e.code());
                    }
                }
            }
            try (InputStream in = response.body().byteStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            ) {
                String line;
                AssistantSSE sse = null;
                while (!emitter.isCancelled() && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        //初始化消息
                        String event = line.substring(6).trim();
                        line = reader.readLine();
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            sse = new AssistantSSE(StreamEvent.valueByName(event), data);
                        } else {
                            throw new SSEFormatException("Invalid sse format! " + line);
                        }
                    } else if (line.isEmpty() && sse != null) {
                        if (sse.isDone()) {
                            if (emitDone) {
                                emitter.onNext(sse);
                            }
                            sse = null;
                            break;
                        }
                        emitter.onNext(sse);
                        sse = null;
                    } else {
                        throw new SSEFormatException("Invalid sse format! " + line);
                    }
                }
                if (sse != null) {
                    emitter.onNext(sse);
                }
                emitter.onComplete();
            }
        } catch (Throwable t) {
            onFailure(call, t);
        }
    }

    private void errorEventThrown(AssistantSSE sse) throws JsonProcessingException {
        if (sse.getEvent().equals(StreamEvent.ERROR)) {
            OpenAiError error = mapper.readValue(
                    sse.getData(),
                    OpenAiError.class);
            throw new OpenAiHttpException(error, null, 0);
        }
    }

    @Override
    public void onFailure(Call<ResponseBody> call, Throwable t) {
        emitter.onError(t);
    }
}

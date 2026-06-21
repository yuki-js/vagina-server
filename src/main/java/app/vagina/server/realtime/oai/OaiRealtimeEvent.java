package app.vagina.server.realtime.oai;

import java.util.List;

/**
 * Inbound OpenAI Realtime events as a sealed type set, plus the value types they carry. Aggregated
 * into one file (judgment 8); mirrors the consumed subset of the Dart {@code realtime_event.dart}.
 *
 * <p>Only the events {@link OaiRealtimeAdapter} actually projects are modelled here — the same set
 * the Dart adapter subscribes to. {@link OaiRealtimeEventParser} maps a raw OpenAI JSON frame onto
 * one of these; an unmodelled {@code type} is dropped by the parser rather than represented, exactly
 * as the Dart binding ignores events it has no typed stream for.
 */
public sealed interface OaiRealtimeEvent {

  /** The OpenAI event {@code type}. */
  String type();

  // ---------------------------------------------------------------------------
  // Value types (mirror the Dart fromJson value objects)
  // ---------------------------------------------------------------------------

  /** A conversation handle; only {@code id} is consumed. */
  record Conversation(String id) {}

  /** A session handle; identity/voice/instructions are kept for completeness, id is what matters. */
  record Session(String id, String model, String voice, String instructions) {}

  /** OpenAI error detail; {@code code} falls back to {@code type} when absent. */
  record ErrorDetail(String type, String code, String message, String param) {}

  /**
   * One content part of a conversation item. {@code type} is the OpenAI part token ({@code
   * input_text} / {@code output_text} / {@code input_audio} / {@code output_audio} / {@code
   * input_image} / ...); the rest are nullable depending on kind.
   */
  record ContentPart(
      String type, String text, String audio, String transcript, String detail, String imageUrl) {}

  /** One conversation item as echoed by OpenAI; mirrors the Dart {@code OaiRealtimeConversationItem}. */
  record ConversationItem(
      String id,
      String type,
      String status,
      String role,
      List<ContentPart> content,
      String callId,
      String name,
      String arguments,
      String output) {}

  // ---------------------------------------------------------------------------
  // Session / conversation lifecycle
  // ---------------------------------------------------------------------------

  /** {@code session.created}: the downstream session is live; maps to "connected" upstream. */
  record SessionCreated(Session session) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "session.created";
    }
  }

  /** {@code conversation.created}: carries the conversation id projected onto the thread. */
  record ConversationCreated(Conversation conversation) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "conversation.created";
    }
  }

  /** {@code conversation.item.created}: a new item (user echo, tool output, ...) was added. */
  record ConversationItemCreated(String previousItemId, ConversationItem item)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "conversation.item.created";
    }
  }

  /** {@code conversation.item.deleted}: an item was removed. */
  record ConversationItemDeleted(String itemId) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "conversation.item.deleted";
    }
  }

  // ---------------------------------------------------------------------------
  // Input-audio transcription (user audio one-shot)
  // ---------------------------------------------------------------------------

  /** {@code conversation.item.input_audio_transcription.delta}: incremental user transcript. */
  record InputAudioTranscriptionDelta(String itemId, Integer contentIndex, String delta)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "conversation.item.input_audio_transcription.delta";
    }
  }

  /** {@code conversation.item.input_audio_transcription.completed}: final user transcript. */
  record InputAudioTranscriptionCompleted(String itemId, Integer contentIndex, String transcript)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "conversation.item.input_audio_transcription.completed";
    }
  }

  // ---------------------------------------------------------------------------
  // VAD
  // ---------------------------------------------------------------------------

  /** {@code input_audio_buffer.speech_started}: server VAD detected speech start. */
  record InputAudioBufferSpeechStarted(String itemId) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "input_audio_buffer.speech_started";
    }
  }

  /** {@code input_audio_buffer.speech_stopped}: server VAD detected speech stop. */
  record InputAudioBufferSpeechStopped(String itemId) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "input_audio_buffer.speech_stopped";
    }
  }

  // ---------------------------------------------------------------------------
  // Response output items
  // ---------------------------------------------------------------------------

  /** {@code response.output_item.added}: an output item (message/functionCall) began. */
  record ResponseOutputItemAdded(String responseId, Integer outputIndex, ConversationItem item)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_item.added";
    }
  }

  /** {@code response.output_item.done}: an output item finished, with its terminal status. */
  record ResponseOutputItemDone(String responseId, Integer outputIndex, ConversationItem item)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_item.done";
    }
  }

  // ---------------------------------------------------------------------------
  // Response content parts
  // ---------------------------------------------------------------------------

  /** {@code response.content_part.added}: a content part of an assistant message began. */
  record ResponseContentPartAdded(String itemId, Integer contentIndex, ContentPart part)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.content_part.added";
    }
  }

  /** {@code response.content_part.done}: a content part finished. */
  record ResponseContentPartDone(String itemId, Integer contentIndex, ContentPart part)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.content_part.done";
    }
  }

  // ---------------------------------------------------------------------------
  // Assistant text
  // ---------------------------------------------------------------------------

  /** {@code response.output_text.delta}: incremental assistant text. */
  record ResponseOutputTextDelta(String itemId, Integer contentIndex, String delta)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_text.delta";
    }
  }

  /** {@code response.output_text.done}: final assistant text (or just a done marker). */
  record ResponseOutputTextDone(String itemId, Integer contentIndex, String text)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_text.done";
    }
  }

  // ---------------------------------------------------------------------------
  // Assistant audio
  // ---------------------------------------------------------------------------

  /** {@code response.output_audio.delta}: one base64 PCM chunk of assistant audio. */
  record ResponseOutputAudioDelta(String itemId, Integer contentIndex, String delta)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_audio.delta";
    }
  }

  /** {@code response.output_audio.done}: assistant audio for this part is complete. */
  record ResponseOutputAudioDone(String itemId, Integer contentIndex) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_audio.done";
    }
  }

  /** {@code response.output_audio_transcript.delta}: incremental assistant audio transcript. */
  record ResponseOutputAudioTranscriptDelta(String itemId, Integer contentIndex, String delta)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_audio_transcript.delta";
    }
  }

  /** {@code response.output_audio_transcript.done}: final assistant audio transcript. */
  record ResponseOutputAudioTranscriptDone(String itemId, Integer contentIndex, String transcript)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.output_audio_transcript.done";
    }
  }

  // ---------------------------------------------------------------------------
  // Function calls
  // ---------------------------------------------------------------------------

  /** {@code response.function_call_arguments.delta}: incremental tool-call arguments. */
  record ResponseFunctionCallArgumentsDelta(String itemId, String callId, String delta)
      implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.function_call_arguments.delta";
    }
  }

  /** {@code response.function_call_arguments.done}: final tool-call name/arguments. */
  record ResponseFunctionCallArgumentsDone(
      String itemId, String callId, String name, String arguments) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.function_call_arguments.done";
    }
  }

  // ---------------------------------------------------------------------------
  // Response lifecycle
  // ---------------------------------------------------------------------------

  /**
   * {@code response.created}: OpenAI started a new response. Marks the beginning of an active
   * response; used by {@code OaiRealtimeAdapter} to guard {@code interrupt()} calls.
   */
  record ResponseCreated(String responseId) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.created";
    }
  }

  /**
   * {@code response.done}: the in-flight response finished (status may be {@code completed},
   * {@code cancelled}, or {@code failed}). Clears the active-response flag.
   */
  record ResponseDone(String responseId, String status) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "response.done";
    }
  }

  // ---------------------------------------------------------------------------
  // Error
  // ---------------------------------------------------------------------------

  /** {@code error}: an OpenAI-side error detail. */
  record ErrorEvent(ErrorDetail error) implements OaiRealtimeEvent {
    @Override
    public String type() {
      return "error";
    }
  }
}

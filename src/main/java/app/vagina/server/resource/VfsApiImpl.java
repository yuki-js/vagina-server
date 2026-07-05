package app.vagina.server.resource;

import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.VfsFileData;
import app.vagina.server.generated.api.VfsApi;
import app.vagina.server.generated.model.JsonRpcVersion;
import app.vagina.server.generated.model.VfsFile;
import app.vagina.server.generated.model.VfsMethod;
import app.vagina.server.generated.model.VfsRpcError;
import app.vagina.server.generated.model.VfsRpcRequest;
import app.vagina.server.generated.model.VfsRpcResponse;
import app.vagina.server.generated.model.VfsRpcResult;
import app.vagina.server.service.VfsFileService;
import app.vagina.server.support.Authenticated;
import app.vagina.server.support.AuthenticatedUser;
import app.vagina.server.usecase.VfsUsecase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
@Path("/vfs/rpc")
@Authenticated
public class VfsApiImpl implements VfsApi {
  private static final int JSON_RPC_INVALID_PARAMS = -32602;
  private static final int JSON_RPC_FILE_NOT_FOUND = -32004;
  private static final int JSON_RPC_CONFLICT = -32009;
  private static final int JSON_RPC_INTERNAL_ERROR = -32603;

  @Inject VfsUsecase vfsUsecase;
  @Inject AuthenticatedUser authenticatedUser;

  @Override
  public Response vfsRpc(VfsRpcRequest vfsRpcRequest) {
    Long userId = authenticatedUser.get().getId();

    try {
      return switch (vfsRpcRequest.getMethod()) {
        case LIST -> listResponse(userId, vfsRpcRequest);
        case READ -> readResponse(userId, vfsRpcRequest);
        case WRITE -> writeResponse(userId, vfsRpcRequest);
        case MOVE -> moveResponse(userId, vfsRpcRequest);
        case DELETE -> deleteResponse(userId, vfsRpcRequest);
      };
    } catch (ValidationException e) {
      return Response.ok(
              errorResponse(
                  vfsRpcRequest.getId(),
                  JSON_RPC_INVALID_PARAMS,
                  e.getMessage(),
                  buildRequestData(vfsRpcRequest)))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.ok(
              errorResponse(
                  vfsRpcRequest.getId(),
                  JSON_RPC_INVALID_PARAMS,
                  e.getMessage(),
                  buildRequestData(vfsRpcRequest)))
          .build();
    } catch (IllegalStateException e) {
      return Response.ok(
              errorResponse(
                  vfsRpcRequest.getId(),
                  toStateErrorCode(e.getMessage()),
                  e.getMessage(),
                  buildRequestData(vfsRpcRequest)))
          .build();
    }
  }

  private Response listResponse(Long userId, VfsRpcRequest request) {
    List<String> entries =
        vfsUsecase.list(userId, request.getParams().getPath(), request.getParams().getRecursive());
    return Response.ok(successResponse(request.getId(), new VfsRpcResult().entries(entries)))
        .build();
  }

  private Response readResponse(Long userId, VfsRpcRequest request) {
    Optional<VfsFileData> file = vfsUsecase.read(userId, request.getParams().getPath());
    if (file.isEmpty()) {
      return Response.ok(
              errorResponse(
                  request.getId(),
                  JSON_RPC_FILE_NOT_FOUND,
                  "File not found",
                  buildRequestData(request)))
          .build();
    }

    return Response.ok(
            successResponse(request.getId(), new VfsRpcResult()._file(toGeneratedFile(file.get()))))
        .build();
  }

  private Response writeResponse(Long userId, VfsRpcRequest request) {
    VfsFileData file =
        vfsUsecase.write(userId, request.getParams().getPath(), request.getParams().getContent());
    return Response.ok(
            successResponse(request.getId(), new VfsRpcResult()._file(toGeneratedFile(file))))
        .build();
  }

  private Response moveResponse(Long userId, VfsRpcRequest request) {
    VfsFileService.MoveResult moveResult =
        vfsUsecase.move(userId, request.getParams().getFromPath(), request.getParams().getToPath());
    return Response.ok(
            successResponse(
                request.getId(),
                new VfsRpcResult().fromPath(moveResult.fromPath()).toPath(moveResult.toPath())))
        .build();
  }

  private Response deleteResponse(Long userId, VfsRpcRequest request) {
    String path = request.getParams().getPath();
    boolean deleted = vfsUsecase.delete(userId, path);
    if (!deleted) {
      return Response.ok(
              errorResponse(
                  request.getId(),
                  JSON_RPC_FILE_NOT_FOUND,
                  "File not found",
                  buildRequestData(request)))
          .build();
    }
    return Response.ok(successResponse(request.getId(), new VfsRpcResult().path(path))).build();
  }

  private VfsRpcResponse successResponse(String requestId, VfsRpcResult result) {
    return new VfsRpcResponse().jsonrpc(JsonRpcVersion._2_0).id(requestId).result(result);
  }

  private VfsRpcResponse errorResponse(
      String requestId, int code, String message, Map<String, Object> data) {
    VfsRpcError error =
        new VfsRpcError().code(code).message(message).data(new LinkedHashMap<>(data));
    return new VfsRpcResponse().jsonrpc(JsonRpcVersion._2_0).id(requestId).error(error);
  }

  private int toStateErrorCode(String message) {
    if (VfsFileService.ERROR_SOURCE_FILE_NOT_FOUND.equals(message)) {
      return JSON_RPC_FILE_NOT_FOUND;
    }
    if (VfsFileService.ERROR_DESTINATION_ALREADY_EXISTS.equals(message)) {
      return JSON_RPC_CONFLICT;
    }
    return JSON_RPC_INTERNAL_ERROR;
  }

  private Map<String, Object> buildRequestData(VfsRpcRequest request) {
    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
    VfsMethod method = request.getMethod();
    if (method != null) {
      data.put("method", method.toString());
    }
    if (request.getParams() != null) {
      if (request.getParams().getPath() != null) {
        data.put("path", request.getParams().getPath());
      }
      if (request.getParams().getFromPath() != null) {
        data.put("fromPath", request.getParams().getFromPath());
      }
      if (request.getParams().getToPath() != null) {
        data.put("toPath", request.getParams().getToPath());
      }
    }
    return data;
  }

  private VfsFile toGeneratedFile(VfsFileData file) {
    return new VfsFile().path(file.getPath()).content(file.getContent());
  }
}

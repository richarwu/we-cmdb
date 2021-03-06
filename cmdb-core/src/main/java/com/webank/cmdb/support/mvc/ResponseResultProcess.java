package com.webank.cmdb.support.mvc;

import java.rmi.ServerException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.google.common.collect.Lists;
import com.webank.cmdb.dto.CustomResponseDto;
import com.webank.cmdb.dto.ResponseDto;
import com.webank.cmdb.support.exception.BatchChangeException;
import com.webank.cmdb.support.exception.BatchChangeException.ExceptionHolder;
import com.webank.cmdb.support.exception.CmdbException;
import com.webank.cmdb.support.exception.InvalidArgumentException;

@ControllerAdvice
public class ResponseResultProcess implements ResponseBodyAdvice<Object> {
    private static final String ERROR_SERVER = "Error happened in server.";
    private static final String SUCCESS = "Success";

    private final static Logger logger = LoggerFactory.getLogger(ResponseResultProcess.class);
    
    public static final String MSG_ERR_CODE_PREFIX = "cmdb.core.msg.errorcode.";

    public static final Locale DEF_LOCALE = Locale.ENGLISH;

    @Autowired
    private MessageSource messageSource;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {

        if (request.getURI().getPath().contains("swagger-resources") || request.getURI().getPath().contains("api-docs")) {
            return body;
        }

        if (body instanceof CustomResponseDto) {
            return body;
        }

        if (!(body instanceof ResponseDto)) {
            if (body == null) {
                body = new Object[0];
            }
            return new ResponseDto(ResponseDto.STATUS_OK, body);
        } else {
            if (ERROR_SERVER.equals(((ResponseDto) body).getStatusMessage())) {
                response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return body;
        }
    }

    @ResponseBody
    @ExceptionHandler(Exception.class)
    public ResponseDto<?> handleException(HttpServletRequest request, final Exception ex,
            HttpServletResponse response) {
        logger.warn("Get exception:", ex);
        if (ex instanceof InvalidArgumentException) {
            InvalidArgumentException invalidArgExp = (InvalidArgumentException) ex;
            return new ResponseDto<Object>(ResponseDto.STATUS_ERROR_INVALID_ARGUMENT, invalidArgExp.getCauseData(), determineI18nErrorMessage(request, invalidArgExp));
        } else if (ex instanceof MethodArgumentNotValidException) {
            FieldError fieldError = ((MethodArgumentNotValidException) ex).getBindingResult().getFieldError();
            return new ResponseDto<String>(ResponseDto.STATUS_ERROR_INVALID_ARGUMENT, null, String.format("Please input valid field value.(%s:%s)", fieldError.getField(), fieldError.getRejectedValue()));
        } else if (ex instanceof HttpMessageNotReadableException) {
            return new ResponseDto<String>(ResponseDto.STATUS_ERROR_INVALID_MESSAGE, null, ex.getMessage());
        } else if (ex instanceof ServerException) {
            
            return new ResponseDto(ResponseDto.STATUS_ERROR, null, ex.getMessage());
        } else if (ex instanceof BatchChangeException) {
            return processBatchChangeException(request, ex);
        }
        
        if(ex instanceof CmdbException){
            return new ResponseDto(ResponseDto.STATUS_ERROR, null, determineI18nErrorMessage(request, (CmdbException)ex));
        }
        return new ResponseDto(ResponseDto.STATUS_ERROR, null, ex.getMessage());
    }

    private ResponseDto<?> processBatchChangeException(HttpServletRequest request, Exception ex) {
        BatchChangeException batchChangeExcept = (BatchChangeException) ex;
        List<ExceptionHolder> exceptionHolders = ((BatchChangeException) ex).getExceptionHolders();
        List<Map> rtnData = Lists.newLinkedList();
        exceptionHolders.forEach(exp -> {
            Map<String, Object> dataMap = new HashMap<>();
            if (exp.getCallBackId() != null) {
                dataMap.put("callbackId", exp.getCallBackId());
            }
            dataMap.put("errorMessage", exp.getErrorMessage());
            rtnData.add(dataMap);
        });

        return new ResponseDto(ResponseDto.STATUS_ERROR_BATCH_CHANGE, rtnData, determineI18nErrorMessage(request, batchChangeExcept));
    }
    
    private String determineI18nErrorMessage(HttpServletRequest request, CmdbException ex) {
    	logger.info("I18N:try to find message {}", ex.getErrorCode());
        Locale locale = request.getLocale();
        if (locale == null) {
            locale = DEF_LOCALE;
        }
        if (StringUtils.isNoneBlank(ex.getErrorCode())) {
            String msgCode = MSG_ERR_CODE_PREFIX + ex.getErrorCode();
            try{
                String errMsg = messageSource.getMessage(msgCode, ex.getArgs(), locale);
                logger.info("I18N:found errmsg {} : {}", msgCode, errMsg);
                return errMsg;
            }catch(NoSuchMessageException e1){
                if(logger.isDebugEnabled()) {
                    String errorMessage = String.format("cannot find such message for %s", ex.getErrorCode());
                    logger.debug(errorMessage, e1);
                }
                return ex.getMessage();
            }
        } else {
            return ex.getMessage();
        }
    }
}

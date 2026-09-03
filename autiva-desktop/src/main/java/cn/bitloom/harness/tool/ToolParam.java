package cn.bitloom.harness.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具入参描述注解 — 对标 spring-ai 的 @ToolParam。
 * <p>
 * 标注在工具输入 record 的组件上，由 {@link JsonSchemaGenerator} 读取生成 JSON Schema：
 * <pre>
 * public record Input(
 *     &#64;ToolParam(description = "文件路径") String filePath,
 *     &#64;ToolParam(description = "起始行号", required = false) Integer offset
 * ) {}
 * </pre>
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /** 参数描述（提供给 LLM）。 */
    String description() default "";

    /** 是否必填（默认 true，与 spring-ai 语义一致）。 */
    boolean required() default true;
}

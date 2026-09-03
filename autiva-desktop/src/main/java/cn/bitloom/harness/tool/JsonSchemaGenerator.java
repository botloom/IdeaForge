package cn.bitloom.harness.tool;

import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema 生成器 — 从工具输入 record 反射生成 schema，替换 spring-ai 的反射生成。
 * <p>
 * 覆盖：String/数值/boolean/枚举/嵌套 record/List&lt;T&gt;/Set/Map/数组；
 * {@link ToolParam} 的 description 与 required（默认必填，与 spring-ai 语义一致）。
 */
public final class JsonSchemaGenerator {

    private JsonSchemaGenerator() {
    }

    /**
     * 生成入参 schema（顶层必须是 object）。
     */
    public static String generate(Class<?> inputType) {
        ObjectNode schema = schemaFor(inputType);
        if (!"object".equals(schema.path("type").asText())) {
            // 非 record 顶层：包装为单参数对象，保持 OpenAI function parameters 结构
            ObjectNode wrapper = JsonUtils.createObject();
            wrapper.put("type", "object");
            ObjectNode props = wrapper.putObject("properties");
            props.set("value", schema);
            ArrayNode required = wrapper.putArray("required");
            required.add("value");
            schema = wrapper;
        }
        return JsonUtils.toJson(schema);
    }

    private static ObjectNode schemaFor(Type type) {
        ObjectNode node = JsonUtils.createObject();
        if (type instanceof Class<?> clazz) {
            return schemaForClass(clazz, node);
        }
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rawClass) {
                if (Collection.class.isAssignableFrom(rawClass)) {
                    node.put("type", "array");
                    Type element = pt.getActualTypeArguments()[0];
                    node.set("items", schemaFor(element));
                    return node;
                }
                if (Map.class.isAssignableFrom(rawClass)) {
                    node.put("type", "object");
                    node.put("additionalProperties", true);
                    return node;
                }
            }
            return schemaForClass((Class<?>) raw, node);
        }
        if (type instanceof GenericArrayType) {
            node.put("type", "array");
            node.set("items", schemaFor(((GenericArrayType) type).getGenericComponentType()));
            return node;
        }
        node.put("type", "string");
        return node;
    }

    private static ObjectNode schemaForClass(Class<?> clazz, ObjectNode node) {
        // 枚举：string + enum 值列表
        if (clazz.isEnum()) {
            node.put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object constant : clazz.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return node;
        }

        // record：object + properties + required（递归嵌套）
        if (clazz.isRecord()) {
            node.put("type", "object");
            ObjectNode properties = node.putObject("properties");
            ArrayNode required = node.putArray("required");
            for (RecordComponent rc : clazz.getRecordComponents()) {
                ObjectNode prop = schemaFor(rc.getGenericType());
                ToolParam param = rc.getAnnotation(ToolParam.class);
                if (param != null && !param.description().isEmpty()) {
                    prop.put("description", param.description());
                }
                properties.set(rc.getName(), prop);
                if (param == null || param.required()) {
                    required.add(rc.getName());
                }
            }
            return node;
        }

        // 数组类
        if (clazz.isArray()) {
            node.put("type", "array");
            node.set("items", schemaFor(clazz.getComponentType()));
            return node;
        }

        // 基本类型与包装
        if (clazz == String.class || clazz == char.class || Character.class == clazz
                || CharSequence.class.isAssignableFrom(clazz)
                || Temporal.class.isAssignableFrom(clazz)) {
            node.put("type", "string");
            return node;
        }
        if (clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class
                || clazz == short.class || clazz == Short.class
                || clazz == byte.class || clazz == Byte.class
                || clazz == BigInteger.class) {
            node.put("type", "integer");
            return node;
        }
        if (clazz == double.class || clazz == Double.class
                || clazz == float.class || clazz == Float.class
                || clazz == BigDecimal.class
                || Number.class.isAssignableFrom(clazz)) {
            node.put("type", "number");
            return node;
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            node.put("type", "boolean");
            return node;
        }
        if (Collection.class.isAssignableFrom(clazz)) {
            // 原始类型集合（List / Set）：元素未知，宽松处理
            node.put("type", "array");
            node.putObject("items");
            return node;
        }
        if (Map.class.isAssignableFrom(clazz)) {
            node.put("type", "object");
            node.put("additionalProperties", true);
            return node;
        }
        // 兜底：未知类型按 string 处理（对齐 spring-ai 宽松行为）
        node.put("type", "string");
        return node;
    }
}

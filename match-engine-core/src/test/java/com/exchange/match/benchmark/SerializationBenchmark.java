package com.exchange.match.benchmark;

import com.alibaba.fastjson2.JSON;
import com.exchange.match.event.OrderCommand;
import com.exchange.common.proto.OrderProto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * ============================================
 * Phase 2: Serialization Performance Benchmark
 * JSON vs Protobuf
 * ============================================
 *
 * 测试目标：
 * 1. JSON (Jackson) 序列化/反序列化延迟
 * 2. JSON (FastJSON2) 序列化/反序列化延迟
 * 3. Protobuf 序列化/反序列化延迟
 *
 * 预期结果：
 * - JSON: 1-5ms
 * - Protobuf: 10-50μs
 * - 提升: 10-50x
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class SerializationBenchmark {

    /**
     * 测试用的 OrderCommand
     */
    private OrderCommand command;

    /**
     * Jackson ObjectMapper
     */
    private ObjectMapper objectMapper;

    /**
     * JSON 字节数组（预序列化）
     */
    private byte[] jsonBytes;

    /**
     * Protobuf 字节数组（预序列化）
     */
    private byte[] protoBytes;

    @Setup
    public void setup() throws Exception {
        // 创建测试对象
        command = new OrderCommand();
        command.setOrderId(12345678L);
        command.setUserId(9876543L);
        command.setSymbol("BTCUSDT");
        command.setSide("BUY");
        command.setOrderType("LIMIT");
        command.setPrice("50000.50");
        command.setQuantity("1.5");
        command.setEventType("ORDER_SUBMIT");
        command.setTimestamp(System.currentTimeMillis());
        command.setClientOrderId("client-order-001");

        // Jackson
        objectMapper = new ObjectMapper();
        jsonBytes = objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);

        // Protobuf
        OrderProto.OrderCommand proto = convertToProto(command);
        protoBytes = proto.toByteArray();
    }

    // ==================== JSON (Jackson) ====================

    @Benchmark
    public void testJacksonSerialize(Blackhole blackhole) throws Exception {
        String json = objectMapper.writeValueAsString(command);
        blackhole.consume(json);
    }

    @Benchmark
    public void testJacksonDeserialize(Blackhole blackhole) throws Exception {
        OrderCommand cmd = objectMapper.readValue(jsonBytes, OrderCommand.class);
        blackhole.consume(cmd);
    }

    // ==================== JSON (FastJSON2) ====================

    @Benchmark
    public void testFastJson2Serialize(Blackhole blackhole) {
        String json = JSON.toJSONString(command);
        blackhole.consume(json);
    }

    @Benchmark
    public void testFastJson2Deserialize(Blackhole blackhole) {
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        OrderCommand cmd = JSON.parseObject(json, OrderCommand.class);
        blackhole.consume(cmd);
    }

    // ==================== Protobuf ====================

    @Benchmark
    public void testProtobufSerialize(Blackhole blackhole) {
        OrderProto.OrderCommand proto = convertToProto(command);
        byte[] bytes = proto.toByteArray();
        blackhole.consume(bytes);
    }

    @Benchmark
    public void testProtobufDeserialize(Blackhole blackhole) throws Exception {
        OrderProto.OrderCommand proto = OrderProto.OrderCommand.parseFrom(protoBytes);
        OrderCommand cmd = convertFromProto(proto);
        blackhole.consume(cmd);
    }

    // ==================== 对比测试：完整流程 ====================

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void testJacksonRoundTrip(Blackhole blackhole) throws Exception {
        // Serialize
        String json = objectMapper.writeValueAsString(command);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        blackhole.consume(bytes);

        // Deserialize
        OrderCommand cmd = objectMapper.readValue(bytes, OrderCommand.class);
        blackhole.consume(cmd);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void testProtobufRoundTrip(Blackhole blackhole) throws Exception {
        // Serialize
        OrderProto.OrderCommand proto = convertToProto(command);
        byte[] bytes = proto.toByteArray();
        blackhole.consume(bytes);

        // Deserialize
        OrderProto.OrderCommand parsed = OrderProto.OrderCommand.parseFrom(bytes);
        OrderCommand cmd = convertFromProto(parsed);
        blackhole.consume(cmd);
    }

    // ==================== Helper Methods ====================

    private OrderProto.OrderCommand convertToProto(OrderCommand cmd) {
        return OrderProto.OrderCommand.newBuilder()
            .setOrderId(cmd.getOrderId())
            .setUserId(cmd.getUserId())
            .setSymbol(cmd.getSymbol())
            .setSide(cmd.getSide())
            .setOrderType(cmd.getOrderType())
            .setPrice(cmd.getPrice())
            .setQuantity(cmd.getQuantity())
            .setEventType(cmd.getEventType())
            .setTimestamp(cmd.getTimestamp())
            .setClientOrderId(cmd.getClientOrderId())
            .build();
    }

    private OrderCommand convertFromProto(OrderProto.OrderCommand proto) {
        OrderCommand cmd = new OrderCommand();
        cmd.setOrderId(proto.getOrderId());
        cmd.setUserId(proto.getUserId());
        cmd.setSymbol(proto.getSymbol());
        cmd.setSide(proto.getSide());
        cmd.setOrderType(proto.getOrderType());
        cmd.setPrice(proto.getPrice());
        cmd.setQuantity(proto.getQuantity());
        cmd.setEventType(proto.getEventType());
        cmd.setTimestamp(proto.getTimestamp());
        cmd.setClientOrderId(proto.getClientOrderId());
        return cmd;
    }
}

package com.exchange.match.benchmark;

import com.exchange.match.model.Order;
import com.exchange.match.orderbook.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ============================================
 * JMH Performance Benchmark for Match Engine
 * ============================================
 *
 * 测试目标：
 * 1. 订单提交延迟（P50, P99, P999）
 * 2. 撮合吞吐量（orders/second）
 * 3. 撤单性能
 *
 * 运行方式：
 * mvn test-compile exec:java \
 *   -Dexec.mainClass="org.openjdk.jmh.Main" \
 *   -Dexec.classpathScope=test \
 *   -Dexec.args="MatchEngineBenchmark"
 *
 * 或者：
 * java -jar target/benchmarks.jar
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 5, time = 10)
@Fork(1)
public class MatchEngineBenchmark {

    /**
     * 订单簿实例
     */
    private OrderBook orderBook;

    /**
     * 预生成的订单数组（避免测试时创建对象）
     */
    private Order[] orders;

    /**
     * 订单索引
     */
    private int orderIndex;

    /**
     * 基础价格（BTCUSDT ≈ 50000）
     */
    private static final long BASE_PRICE = 50000_00000000L; // 8位小数

    /**
     * 基础数量（0.01 BTC）
     */
    private static final long BASE_QTY = 1000000L; // 8位小数

    @Setup
    public void setup() {
        orderBook = new OrderBook("BTCUSDT");
        orders = new Order[10000];

        // 预生成订单（50%买单，50%卖单，价格有随机波动）
        for (int i = 0; i < orders.length; i++) {
            orders[i] = createRandomOrder(i);
        }

        orderIndex = 0;
    }

    /**
     * 创建随机订单
     */
    private Order createRandomOrder(int index) {
        Order order = new Order();
        order.setOrderId((long) index);
        order.setUserId((long) (index % 1000)); // 1000个用户
        order.setSymbol("BTCUSDT");

        // 50%买单，50%卖单
        order.setSide(index % 2 == 0 ? 0 : 1); // 0=BUY, 1=SELL
        order.setType(0); // LIMIT

        // 价格在 BASE_PRICE 的 ±10% 范围内波动
        double priceVariation = 0.9 + (index % 20) * 0.01; // 0.9 ~ 1.1
        long price = (long) (BASE_PRICE * priceVariation);
        order.setPrice(new BigDecimal(price).movePointLeft(8)); // 转换为 BigDecimal

        // 数量固定
        order.setQuantity(new BigDecimal(BASE_QTY).movePointLeft(8));
        order.setFilledQuantity(BigDecimal.ZERO);

        return order;
    }

    /**
     * 测试：添加订单（撮合）
     *
     * 目标：P99 < 0.3ms (300μs)
     */
    @Benchmark
    public void testAddOrder(Blackhole blackhole) {
        // 获取下一个订单（循环使用）
        Order order = orders[orderIndex % orders.length];
        orderIndex++;

        // 重置订单状态（为了可重复测试）
        order.setFilledQuantity(BigDecimal.ZERO);

        // 执行撮合
        var trades = orderBook.addOrder(order);

        // 消费结果（防止 JVM 优化掉）
        blackhole.consume(trades);

        // 每 1000 个订单清理一次订单簿（防止无限增长）
        if (orderIndex % 1000 == 0) {
            resetOrderBook();
        }
    }

    /**
     * 测试：添加买单深度
     *
     * 场景：大量买单进入，构建深度
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void testAddBidDepth(Blackhole blackhole) {
        Order order = createBidOrder(orderIndex);
        orderIndex++;

        var trades = orderBook.addOrder(order);
        blackhole.consume(trades);

        if (orderIndex % 500 == 0) {
            resetOrderBook();
        }
    }

    /**
     * 测试：添加卖单深度
     *
     * 场景：大量卖单进入，构建深度
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void testAddAskDepth(Blackhole blackhole) {
        Order order = createAskOrder(orderIndex);
        orderIndex++;

        var trades = orderBook.addOrder(order);
        blackhole.consume(trades);

        if (orderIndex % 500 == 0) {
            resetOrderBook();
        }
    }

    /**
     * 测试：撮合交易
     *
     * 场景：一个卖单吃掉多个买单
     */
    @Benchmark
    public void testMatchTrade(Blackhole blackhole) {
        // 先添加一些买单构建深度
        for (int i = 0; i < 10; i++) {
            Order bid = createBidOrder(i);
            long price = BASE_PRICE + i * 100_000000L;
            bid.setPrice(new BigDecimal(price).movePointLeft(8));
            orderBook.addOrder(bid);
        }

        // 一个大卖单来撮合
        Order ask = createAskOrder(99999);
        ask.setQuantity(new BigDecimal(BASE_QTY * 5).movePointLeft(8)); // 大单
        var trades = orderBook.addOrder(ask);

        blackhole.consume(trades);
        resetOrderBook();
    }

    /**
     * 测试：撤单
     *
     * 场景：订单簿中有订单，执行撤单
     */
    @Benchmark
    public void testCancelOrder(Blackhole blackhole) {
        // 先添加一个订单
        long orderId = orderIndex++;
        Order order = createRandomOrder((int) orderId);
        order.setOrderId(orderId);
        orderBook.addOrder(order);

        // 执行撤单
        boolean result = orderBook.cancelOrder(orderId);
        blackhole.consume(result);

        if (orderIndex % 1000 == 0) {
            resetOrderBook();
        }
    }

    /**
     * 创建买单
     */
    private Order createBidOrder(int index) {
        Order order = new Order();
        order.setOrderId((long) index);
        order.setUserId(1L);
        order.setSymbol("BTCUSDT");
        order.setSide(0); // BUY
        order.setType(0); // LIMIT
        long price = BASE_PRICE - (index % 100) * 100_000000L;
        order.setPrice(new BigDecimal(price).movePointLeft(8));
        order.setQuantity(new BigDecimal(BASE_QTY).movePointLeft(8));
        order.setFilledQuantity(BigDecimal.ZERO);
        return order;
    }

    /**
     * 创建卖单
     */
    private Order createAskOrder(int index) {
        Order order = new Order();
        order.setOrderId((long) index);
        order.setUserId(1L);
        order.setSymbol("BTCUSDT");
        order.setSide(1); // SELL
        order.setType(0); // LIMIT
        long price = BASE_PRICE + (index % 100) * 100_000000L;
        order.setPrice(new BigDecimal(price).movePointLeft(8));
        order.setQuantity(new BigDecimal(BASE_QTY).movePointLeft(8));
        order.setFilledQuantity(BigDecimal.ZERO);
        return order;
    }

    /**
     * 重置订单簿（清理状态）
     */
    private void resetOrderBook() {
        orderBook = new OrderBook("BTCUSDT");
    }

    /**
     * 测试组：完整撮合流程
     *
     * 包含：下单 → 撮合 → 撤单
     */
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Benchmark
    public void testFullWorkflow(Blackhole blackhole) {
        // 1. 添加买单
        Order bid = createBidOrder(1);
        var trades1 = orderBook.addOrder(bid);
        blackhole.consume(trades1);

        // 2. 添加卖单（撮合）
        Order ask = createAskOrder(2);
        ask.setPrice(bid.getPrice()); // 同价撮合
        var trades2 = orderBook.addOrder(ask);
        blackhole.consume(trades2);

        // 3. 清理
        resetOrderBook();
    }
}

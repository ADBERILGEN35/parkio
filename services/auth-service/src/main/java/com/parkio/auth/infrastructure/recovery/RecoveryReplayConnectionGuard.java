package com.parkio.auth.infrastructure.recovery;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.flywaydb.core.Flyway;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Layer 2 of PR #295 review B6: in the command context, every connection is checked against the
 * database the preflight accepted, before anything uses it. Registered by
 * {@link RecoveryReplayLaunch} in the command context only, after the preflight passed:
 *
 * <ul>
 *   <li>a {@link BeanPostProcessor} wraps every {@link DataSource} bean, so each
 *       {@code getConnection} re-reads {@code pg_control_system()} and {@code current_database()}
 *       on the connection it returns, before the caller runs a statement;</li>
 *   <li>a {@link FlywayMigrationStrategy} reads the identity through the data source Flyway actually
 *       uses, which can differ from the bean, before {@code migrate()}.</li>
 * </ul>
 *
 * <p>Any other identity is refused (22): the connection is closed and the refusal recorded, so the
 * launch reports it whatever failed afterwards. This catches redirect settings the preflight does
 * not list.
 */
final class RecoveryReplayConnectionGuard {

    private final String checkedIdentity;
    private final AtomicReference<RecoveryReplayRefusal> refusal = new AtomicReference<>();

    RecoveryReplayConnectionGuard(String checkedIdentity) {
        this.checkedIdentity = checkedIdentity;
    }

    /** Installs both checks in the command context, before its beans are created. */
    void install(ConfigurableApplicationContext context) {
        context.getBeanFactory().addBeanPostProcessor(dataSources());
        context.getBeanFactory().registerSingleton("recoveryReplayFlywayMigrationStrategy", flyway());
    }

    RecoveryReplayRefusal refusal() {
        return refusal.get();
    }

    BeanPostProcessor dataSources() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource ? guarded(dataSource) : bean;
            }
        };
    }

    FlywayMigrationStrategy flyway() {
        return flyway -> {
            verify(flyway);
            flyway.migrate();
        };
    }

    /**
     * {@code dataSource} behind an interface proxy that checks every connection it hands out
     * ({@code unwrap} still reaches the pool, for its metrics).
     */
    Object guarded(DataSource dataSource) {
        ProxyFactory proxy = new ProxyFactory(dataSource);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            Method method = invocation.getMethod();
            if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                check(connection);
            }
            return result;
        });
        return proxy.getProxy(dataSource.getClass().getClassLoader());
    }

    private void verify(Flyway flyway) {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            check(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("recovery replay: Flyway's connection could not be checked", ex);
        }
    }

    /** Refuses (and closes) a connection to any database but the checked one. */
    void check(Connection connection) throws SQLException {
        String actual;
        try (PreparedStatement statement = connection.prepareStatement(RecoveryReplayTarget.IDENTITY);
             ResultSet result = statement.executeQuery()) {
            actual = result.next() ? result.getString(1) : null;
        } catch (SQLException ex) {
            refuse(connection, "a connection of the command context could not be identified");
            throw ex;
        }
        if (!checkedIdentity.equals(actual)) {
            refuse(connection, "a connection of the command context reached a database the preflight did not check");
            throw new SQLException("recovery replay: connection refused; it does not reach the checked database");
        }
    }

    private void refuse(Connection connection, String reason) {
        refusal.compareAndSet(null, new RecoveryReplayRefusal(RecoveryReplayExit.TARGET_REFUSED, reason));
        try {
            connection.close();
        } catch (SQLException ignored) {
            // the connection is unusable either way
        }
    }
}

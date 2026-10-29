package com.fastwork.expense.config;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;

/**
 * Tipe JDBC dasar untuk kolom PostgreSQL dengan tipe khusus ({@code citext}, enum).
 *
 * <p>Driver PostgreSQL melaporkan {@code citext} dan enum sebagai {@link Types#OTHER}, sedangkan
 * Hibernate memetakan {@link String} ke {@code varchar}. Nilai juga harus dikirim sebagai parameter
 * bertipe {@code unknown}: kalau dikirim sebagai {@code varchar}, PostgreSQL memakai operator
 * {@code text} (case-sensitive) saat membandingkan kolom {@code citext}, dan menolak konversi ke
 * kolom enum.
 */
public abstract class PostgresOpaqueTextJdbcType extends VarcharJdbcType {

    @Override
    public int getJdbcTypeCode() {
        return Types.OTHER;
    }

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new ValueBinder<>() {
            @Override
            public void bind(PreparedStatement st, X value, int position, WrapperOptions options)
                    throws SQLException {
                st.setObject(position, value, Types.OTHER);
            }

            @Override
            public void bind(
                    CallableStatement st, X value, String parameterName, WrapperOptions options)
                    throws SQLException {
                st.setObject(parameterName, value, Types.OTHER);
            }
        };
    }
}

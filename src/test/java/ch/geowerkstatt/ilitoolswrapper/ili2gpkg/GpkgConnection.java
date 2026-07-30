package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class GpkgConnection implements AutoCloseable {
    private final Connection connection;

    public GpkgConnection(Path gpkgFilePath) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + gpkgFilePath.toAbsolutePath());
    }

    public void assertHasTable(String tableName, Set<String> expectedColumns) {
        try {
            var columns = getColumns(tableName);
            assertEquals(expectedColumns, columns, "Table " + tableName + " does not have the expected columns.");
        } catch (SQLException e) {
            throw new RuntimeException("Error checking table " + tableName, e);
        }
    }

    private Set<String> getColumns(String tableName) throws SQLException {
        Set<String> columns = new java.util.HashSet<>();
        String tableNamePattern = escapeSearchPattern(tableName);
        try (var resultSet = connection.getMetaData().getColumns(null, null, tableNamePattern, null)) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private String escapeSearchPattern(String pattern) throws SQLException {
        String escape = connection.getMetaData().getSearchStringEscape();
        return pattern
                .replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    public void assertData(String tableName, String sortColumn, List<Map<String, Object>> expectedData) {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT * FROM " + tableName + " ORDER BY " + sortColumn)) {
            for (int i = 0; i < expectedData.size(); i++) {
                assertTrue(resultSet.next(), "Expected more rows in table " + tableName + " found only " + i + ".");
                Map<String, Object> expectedRow = expectedData.get(i);
                for (Map.Entry<String, Object> entry : expectedRow.entrySet()) {
                    String columnName = entry.getKey();
                    Object expectedValue = entry.getValue();
                    Object actualValue = resultSet.getObject(columnName);
                    assertEquals(expectedValue, actualValue, "Mismatch in table " + tableName + ", row " + (i + 1) + ", column " + columnName + ".");
                }
            }

            assertFalse(resultSet.next(), "Expected " + expectedData.size() + " rows in table " + tableName + ", got more.");
        } catch (SQLException e) {
            throw new RuntimeException("Error checking data in table " + tableName, e);
        }
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}

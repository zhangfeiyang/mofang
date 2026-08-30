package com.mofang.cubear;

import java.util.Arrays;

/**
 * Minimum-cost assignment via the Jonker-Volgenant shortest augmenting path method, O(n^2 m).
 *
 * <p>The scan produces 54 stickers that must split into exactly nine of each colour. Deciding each
 * sticker on its own throws that constraint away; solving the assignment enforces it, which is what
 * lets two visually identical patches land on the same colour instead of straddling a threshold.
 */
public final class Hungarian {
    private static final double INFINITY = Double.MAX_VALUE / 4;

    private Hungarian() {}

    /**
     * Assigns every row to a distinct column at minimum total cost.
     *
     * @param cost rows by columns, requires rows &lt;= columns
     * @return for each row, the column it was assigned to
     */
    public static int[] solve(double[][] cost) {
        int rows = cost.length;
        if (rows == 0) return new int[0];
        int columns = cost[0].length;
        if (columns < rows) throw new IllegalArgumentException("Needs at least one column per row");

        double[] rowPotential = new double[rows + 1];
        double[] columnPotential = new double[columns + 1];
        int[] columnMatch = new int[columns + 1];
        int[] previousColumn = new int[columns + 1];

        for (int row = 1; row <= rows; row++) {
            columnMatch[0] = row;
            int column = 0;
            double[] slack = new double[columns + 1];
            boolean[] visited = new boolean[columns + 1];
            Arrays.fill(slack, INFINITY);

            do {
                visited[column] = true;
                int currentRow = columnMatch[column];
                int nextColumn = -1;
                double delta = INFINITY;

                for (int candidate = 1; candidate <= columns; candidate++) {
                    if (visited[candidate]) continue;
                    double reduced = cost[currentRow - 1][candidate - 1]
                        - rowPotential[currentRow] - columnPotential[candidate];
                    if (reduced < slack[candidate]) {
                        slack[candidate] = reduced;
                        previousColumn[candidate] = column;
                    }
                    if (slack[candidate] < delta) {
                        delta = slack[candidate];
                        nextColumn = candidate;
                    }
                }

                for (int candidate = 0; candidate <= columns; candidate++) {
                    if (visited[candidate]) {
                        rowPotential[columnMatch[candidate]] += delta;
                        columnPotential[candidate] -= delta;
                    } else {
                        slack[candidate] -= delta;
                    }
                }
                column = nextColumn;
            } while (columnMatch[column] != 0);

            do {
                int previous = previousColumn[column];
                columnMatch[column] = columnMatch[previous];
                column = previous;
            } while (column != 0);
        }

        int[] assignment = new int[rows];
        Arrays.fill(assignment, -1);
        for (int column = 1; column <= columns; column++) {
            if (columnMatch[column] != 0) assignment[columnMatch[column] - 1] = column - 1;
        }
        return assignment;
    }
}

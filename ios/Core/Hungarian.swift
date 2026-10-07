import Foundation

// Port of com.mofang.cubear.Hungarian.java
// Jonker-Volgenant 最短增广路法的最小代价分配,O(n^2 m)。
//
// 扫描得到 54 张贴纸,必须恰好分成每种颜色九张。逐贴纸独立判定会丢掉这个约束;
// 求解分配问题把它强制回来,这让两个视觉相同的贴纸落到同一颜色而不是骑在阈值上。

enum Hungarian {
    private static let INFINITY = Double.greatestFiniteMagnitude / 4

    /// 把每一行分配到不同的列,总代价最小。
    ///
    /// - Parameter cost: 行×列,要求行数 ≤ 列数
    /// - Returns: 每行被分配到的列
    static func solve(_ cost: [[Double]]) -> [Int] {
        let rows = cost.count
        if rows == 0 { return [] }
        let columns = cost[0].count
        precondition(columns >= rows, "Needs at least one column per row")

        var rowPotential = [Double](repeating: 0, count: rows + 1)
        var columnPotential = [Double](repeating: 0, count: columns + 1)
        var columnMatch = [Int](repeating: 0, count: columns + 1)
        var previousColumn = [Int](repeating: 0, count: columns + 1)

        for row in 1...rows {
            columnMatch[0] = row
            var column = 0
            var slack = [Double](repeating: INFINITY, count: columns + 1)
            var visited = [Bool](repeating: false, count: columns + 1)

            repeat {
                visited[column] = true
                let currentRow = columnMatch[column]
                var nextColumn = -1
                var delta = INFINITY

                for candidate in 1...columns {
                    if visited[candidate] { continue }
                    let reduced = cost[currentRow - 1][candidate - 1]
                        - rowPotential[currentRow] - columnPotential[candidate]
                    if reduced < slack[candidate] {
                        slack[candidate] = reduced
                        previousColumn[candidate] = column
                    }
                    if slack[candidate] < delta {
                        delta = slack[candidate]
                        nextColumn = candidate
                    }
                }

                for candidate in 0...columns {
                    if visited[candidate] {
                        rowPotential[columnMatch[candidate]] += delta
                        columnPotential[candidate] -= delta
                    } else {
                        slack[candidate] -= delta
                    }
                }
                column = nextColumn
            } while columnMatch[column] != 0

            repeat {
                let previous = previousColumn[column]
                columnMatch[column] = columnMatch[previous]
                column = previous
            } while column != 0
        }

        var assignment = [Int](repeating: -1, count: rows)
        for column in 1...columns where columnMatch[column] != 0 {
            assignment[columnMatch[column] - 1] = column - 1
        }
        return assignment
    }
}

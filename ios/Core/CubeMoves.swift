import Foundation

// Port of com.mofang.cubear.CubeMoves.java
// 由一个小型整数 3D 魔方模型生成的 facelet 置换。

enum CubeMoves {
    static func apply(_ state: String?, _ move: String?) -> String {
        guard let state = state, state.count == 54, let move = move, !move.isEmpty else { return state ?? "" }
        let chars = Array(move)
        let face = chars[0]
        let turns = move.hasSuffix("2") ? 2 : (move.hasSuffix("'") ? 3 : 1)
        var result = state
        for _ in 0..<turns { result = clockwise(result, face) }
        return result
    }

    static func face(_ state: String, _ face: Character) -> String {
        guard let offset = "URFDLB".firstIndex(of: face) else { return "" }
        let start = "URFDLB".distance(from: "URFDLB".startIndex, to: offset) * 9
        let chars = Array(state)
        guard start + 9 <= chars.count else { return "" }
        return String(chars[start..<(start + 9)])
    }

    private static func clockwise(_ state: String, _ face: Character) -> String {
        guard let axis = normalForFace(face) else { return state }
        let chars = Array(state)
        var output = [Character](repeating: "?", count: 54)
        for index in 0..<54 {
            let sticker = stickerAt(index)
            var moved = sticker
            if sticker.position.dot(axis) == 1 {
                moved = Sticker(position: rotateMinus90(sticker.position, axis),
                                normal: rotateMinus90(sticker.normal, axis))
            }
            output[indexOf(moved)] = chars[index]
        }
        return String(output)
    }

    private static func rotateMinus90(_ vector: Vec, _ axis: Vec) -> Vec {
        let cross = axis.cross(vector)
        let projection = axis.dot(vector)
        return Vec(
            x: -cross.x + axis.x * projection,
            y: -cross.y + axis.y * projection,
            z: -cross.z + axis.z * projection
        )
    }

    private static func normalForFace(_ face: Character) -> Vec? {
        switch face {
        case "U": return Vec(x: 0, y: 1, z: 0)
        case "R": return Vec(x: 1, y: 0, z: 0)
        case "F": return Vec(x: 0, y: 0, z: 1)
        case "D": return Vec(x: 0, y: -1, z: 0)
        case "L": return Vec(x: -1, y: 0, z: 0)
        case "B": return Vec(x: 0, y: 0, z: -1)
        default: return nil
        }
    }

    private static func stickerAt(_ index: Int) -> Sticker {
        let face = index / 9, local = index % 9, row = local / 3, col = local % 3
        switch face {
        case 0: return Sticker(position: Vec(x: col - 1, y: 1, z: row - 1), normal: Vec(x: 0, y: 1, z: 0))
        case 1: return Sticker(position: Vec(x: 1, y: 1 - row, z: 1 - col), normal: Vec(x: 1, y: 0, z: 0))
        case 2: return Sticker(position: Vec(x: col - 1, y: 1 - row, z: 1), normal: Vec(x: 0, y: 0, z: 1))
        case 3: return Sticker(position: Vec(x: col - 1, y: -1, z: 1 - row), normal: Vec(x: 0, y: -1, z: 0))
        case 4: return Sticker(position: Vec(x: -1, y: 1 - row, z: col - 1), normal: Vec(x: -1, y: 0, z: 0))
        default: return Sticker(position: Vec(x: 1 - col, y: 1 - row, z: -1), normal: Vec(x: 0, y: 0, z: -1))
        }
    }

    private static func indexOf(_ target: Sticker) -> Int {
        for i in 0..<54 where stickerAt(i) == target {
            return i
        }
        preconditionFailure("Invalid sticker coordinate")
    }

    private struct Sticker: Equatable {
        let position: Vec
        let normal: Vec
    }

    private struct Vec: Equatable {
        let x: Int, y: Int, z: Int
        func dot(_ other: Vec) -> Int { x * other.x + y * other.y + z * other.z }
        func cross(_ other: Vec) -> Vec {
            Vec(x: y * other.z - z * other.y, y: z * other.x - x * other.z, z: x * other.y - y * other.x)
        }
    }
}

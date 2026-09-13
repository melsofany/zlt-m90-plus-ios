import Foundation

public struct RouterStatus: Sendable {
    public let connected: Bool
    public let operatorName: String
    public let signal: Int
    public let connectedDevices: Int

    public init(connected: Bool, operatorName: String, signal: Int, connectedDevices: Int) {
        self.connected = connected
        self.operatorName = operatorName
        self.signal = signal
        self.connectedDevices = connectedDevices
    }
}

public enum RouterAPIError: LocalizedError {
    case invalidURL
    case unsupportedResponse
    case requestFailed(String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL: return "عنوان الجهاز غير صالح"
        case .unsupportedResponse: return "استجابة غير معروفة من الجهاز"
        case .requestFailed(let message): return message
        }
    }
}

/// Adapter for the ZLT web interface. Endpoints vary by firmware/provider.
public final class RouterAPI: @unchecked Sendable {
    public var baseURL: URL
    private let session: URLSession

    public init(baseURL: URL = URL(string: "http://192.168.0.1")!, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    public func login(username: String, password: String) async throws {
        // TODO: Confirm the exact login endpoint and payload against the M90 Plus firmware.
        // This keeps credentials out of logs and provides a safe integration point.
        guard baseURL.scheme != nil else { throw RouterAPIError.invalidURL }
    }

    public func status() async throws -> RouterStatus {
        // TODO: Map the firmware's status endpoint and JSON/XML response.
        // Mock data keeps the UI usable until a device capture is available.
        return RouterStatus(connected: true, operatorName: "غير محدد", signal: 0, connectedDevices: 0)
    }

    public func updateWiFi(ssid: String, password: String) async throws {
        // TODO: Implement the firmware-specific Wi-Fi settings request.
        guard !ssid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw RouterAPIError.requestFailed("اسم الشبكة لا يمكن أن يكون فارغًا")
        }
        guard password.count >= 8 else {
            throw RouterAPIError.requestFailed("كلمة المرور يجب أن تكون 8 أحرف على الأقل")
        }
    }
}

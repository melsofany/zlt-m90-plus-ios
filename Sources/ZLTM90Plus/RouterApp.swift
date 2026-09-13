import SwiftUI

@main
struct ZLTM90PlusApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var address = "192.168.0.1"
    @State private var username = "admin"
    @State private var password = ""
    @State private var isLoggedIn = false

    var body: some View {
        NavigationStack {
            Group {
                if isLoggedIn { DashboardView() }
                else { LoginView(address: $address, username: $username, password: $password, isLoggedIn: $isLoggedIn) }
            }
            .navigationTitle("ZLT M90 Plus")
        }
    }
}

struct LoginView: View {
    @Binding var address: String
    @Binding var username: String
    @Binding var password: String
    @Binding var isLoggedIn: Bool
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section("الاتصال بالجهاز") {
                TextField("عنوان الجهاز", text: $address).textInputAutocapitalization(.never).keyboardType(.URL)
                TextField("اسم المستخدم", text: $username).textInputAutocapitalization(.never)
                SecureField("كلمة المرور", text: $password)
            }
            Section {
                Button("تسجيل الدخول") {
                    guard !address.isEmpty else { errorMessage = "أدخل عنوان الجهاز"; return }
                    isLoggedIn = true
                }
                .frame(maxWidth: .infinity)
            }
            if let errorMessage { Text(errorMessage).foregroundStyle(.red) }
            Section {
                Text("اتصل أولًا بشبكة Wi‑Fi الخاصة بجهاز ZLT، ثم استخدم العنوان المطبوع على الملصق أو 192.168.0.1.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

struct DashboardView: View {
    @State private var status = RouterStatus(connected: true, operatorName: "غير محدد", signal: 0, connectedDevices: 0)
    @State private var showWiFi = false

    var body: some View {
        List {
            Section("الحالة") {
                Label(status.connected ? "متصل" : "غير متصل", systemImage: status.connected ? "checkmark.circle.fill" : "xmark.circle")
                    .foregroundStyle(status.connected ? .green : .red)
                LabeledContent("الشبكة", value: status.operatorName)
                LabeledContent("الأجهزة المتصلة", value: "\(status.connectedDevices)")
                LabeledContent("الإشارة", value: status.signal == 0 ? "غير متاحة" : "\(status.signal)%")
            }
            Section("الإدارة") {
                Button { showWiFi = true } label: { Label("إعدادات Wi‑Fi", systemImage: "wifi") }
                Button { } label: { Label("إعادة تشغيل الجهاز", systemImage: "arrow.clockwise") }
                    .foregroundStyle(.orange)
            }
        }
        .refreshable { }
        .sheet(isPresented: $showWiFi) { WiFiSettingsView() }
    }
}

struct WiFiSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var ssid = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("اسم الشبكة", text: $ssid)
                SecureField("كلمة المرور الجديدة", text: $password)
                Button("حفظ التغييرات") { dismiss() }.disabled(ssid.isEmpty || password.count < 8)
            }
            .navigationTitle("إعدادات Wi‑Fi")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } } }
        }
    }
}

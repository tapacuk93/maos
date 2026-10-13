// vzgui - the same EFI virtual machine, with a window to look at.
//
// EFI's console is a framebuffer one: the firmware draws text onto the display
// device rather than sending it anywhere. Headless, that means a guest can be
// printing perfectly and nothing observes it - which is indistinguishable from
// a guest printing nothing, and this project has spent enough rounds on that
// particular confusion already.
//
// So this is the same machine as tools/vzefi.swift with a VZVirtualMachineView
// in a window. It exists to be looked at, and to be screenshotted.
//
//     vzgui <disk image>

import AppKit
import Foundation
import Virtualization

func die(_ why: String) -> Never {
    FileHandle.standardError.write(("vzgui: " + why + "\n").data(using: .utf8)!)
    exit(1)
}

let args = CommandLine.arguments
guard args.count >= 2 else { die("usage: vzgui <disk image>") }
let disk = URL(fileURLWithPath: args[1])

let boot = VZEFIBootLoader()
let varsURL = URL(fileURLWithPath: NSTemporaryDirectory())
    .appendingPathComponent("maos-efivars-gui")
try? FileManager.default.removeItem(at: varsURL)
do {
    boot.variableStore = try VZEFIVariableStore(creatingVariableStoreAt: varsURL)
} catch {
    die("could not create the EFI variable store: \(error)")
}

let config = VZVirtualMachineConfiguration()
config.bootLoader = boot
config.cpuCount = 1
config.memorySize = 512 * 1024 * 1024

do {
    let attachment = try VZDiskImageStorageDeviceAttachment(url: disk, readOnly: false)
    config.storageDevices = [VZVirtioBlockDeviceConfiguration(attachment: attachment)]
} catch {
    die("could not attach \(disk.lastPathComponent): \(error)")
}

let graphics = VZVirtioGraphicsDeviceConfiguration()
graphics.scanouts = [VZVirtioGraphicsScanoutConfiguration(widthInPixels: 1280,
                                                          heightInPixels: 800)]
config.graphicsDevices = [graphics]

/*
 * A network, always.
 *
 * This machine is not useful without one: it holds a key, and a key is a thing
 * to reach a backend with. The framework's NAT hands out an address over DHCP
 * and puts the host on the other end of it, which is the same shape the guest
 * sees under QEMU - so the kernel's own DHCP, ARP and TCP take the path they
 * already take and nothing in the guest knows which hypervisor this is.
 */
let net = VZVirtioNetworkDeviceConfiguration()
net.attachment = VZNATNetworkDeviceAttachment()
config.networkDevices = [net]

/*
 * And a keyboard, for the same reason: a window that cannot be typed into is a
 * screenshot. USB rather than the Mac-only one, because the guest's driver is
 * the USB HID path it will use on real hardware.
 */
config.keyboards = [VZUSBKeyboardConfiguration()]
config.pointingDevices = [VZUSBScreenCoordinatePointingDeviceConfiguration()]

let console = VZVirtioConsoleDeviceSerialPortConfiguration()
if let path = ProcessInfo.processInfo.environment["VZEFI_OUT"] {
    FileManager.default.createFile(atPath: path, contents: nil)
    if let out = FileHandle(forWritingAtPath: path) {
        console.attachment = VZFileHandleSerialPortAttachment(
            fileHandleForReading: FileHandle(forReadingAtPath: "/dev/null")!,
            fileHandleForWriting: out)
        config.serialPorts = [console]
    }
}

do {
    try config.validate()
} catch {
    die("this configuration is not one the framework will accept: \(error)")
}

final class App: NSObject, NSApplicationDelegate, VZVirtualMachineDelegate {
    var vm: VZVirtualMachine!
    var window: NSWindow!

    func applicationDidFinishLaunching(_ note: Notification) {
        vm = VZVirtualMachine(configuration: config)
        vm.delegate = self

        let view = VZVirtualMachineView(frame: NSRect(x: 0, y: 0, width: 1280, height: 800))
        view.virtualMachine = vm
        view.capturesSystemKeys = false

        window = NSWindow(contentRect: view.frame,
                          styleMask: [.titled, .closable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "maos (EFI)"
        window.contentView = view
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        vm.start { result in
            if case .failure(let error) = result {
                FileHandle.standardError.write(
                    "vzgui: could not start: \(error)\n".data(using: .utf8)!)
            } else {
                FileHandle.standardError.write("vzgui: started\n".data(using: .utf8)!)
            }
        }
    }

    func guestDidStop(_ vm: VZVirtualMachine) {
        FileHandle.standardError.write("vzgui: the guest halted\n".data(using: .utf8)!)
    }

    func virtualMachine(_ vm: VZVirtualMachine, didStopWithError error: Error) {
        FileHandle.standardError.write("vzgui: stopped: \(error)\n".data(using: .utf8)!)
    }
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()

// © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// SPDX-License-Identifier: EPL-2.0

// S05 prototype: the executable of the hub MSIX package. A packaged desktop
// app's Executable gets no fixed arguments and an unknown working directory,
// so this launcher (the manifest's Application, startup task, execution alias
// and protocol handler all point at it) does what the manifest cannot:
//   - chdir to the package root (the directory holding this executable),
//   - name the publisher cache folder for the daemon (CLOGEM_SHARED_DIR,
//     ADR-0006 decision 5) from the package family name,
//   - run `bin\bb.exe --config hub\bb.edn daemon` (or the arguments it was
//     given: the startup task passes "daemon", the protocol handler
//     `--uri <link>`, the alias whatever the shell typed),
//   - append one line per launch to launcher.log, so the VM test shows where a
//     packaged process's writes under %LOCALAPPDATA% actually land
//     (file-system virtualization) and whether the child bb.exe starts at all.
// Everything observable is written, nothing is read from the user.
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

/// The directory this executable lives in: the package root once installed.
String packageRoot() => File(Platform.resolvedExecutable).parent.path;

/// `<Name>_<PublisherId>` of the package this process belongs to, or null when
/// the process has no package identity (ran from a plain folder).
String? packageFamilyName() {
  if (!Platform.isWindows) return null;
  final kernel32 = DynamicLibrary.open('kernel32.dll');
  // LONG GetCurrentPackageFamilyName(UINT32 *packageFamilyNameLength, PWSTR packageFamilyName)
  final fn = kernel32.lookupFunction<
      Int32 Function(Pointer<Uint32>, Pointer<Uint16>),
      int Function(Pointer<Uint32>, Pointer<Uint16>)>('GetCurrentPackageFamilyName');
  final length = allocBytes<Uint32>(4);
  final buffer = allocBytes<Uint16>(512 * 2);
  try {
    length.value = 512;
    final rc = fn(length, buffer);
    if (rc != 0) return null; // APPMODEL_ERROR_NO_PACKAGE (15700) without identity
    final units = <int>[for (var i = 0; i < length.value; i++) buffer[i]];
    while (units.isNotEmpty && units.last == 0) units.removeLast();
    return String.fromCharCodes(units);
  } finally {
    freeBytes(length);
    freeBytes(buffer);
  }
}

/// A minimal calloc/free over the C runtime, so no package is needed.
final DynamicLibrary _crt = Platform.isWindows ? DynamicLibrary.open('ucrtbase.dll') : DynamicLibrary.process();
final Pointer<Void> Function(int, int) _calloc =
    _crt.lookupFunction<Pointer<Void> Function(IntPtr, IntPtr), Pointer<Void> Function(int, int)>('calloc');
final void Function(Pointer<Void>) _free =
    _crt.lookupFunction<Void Function(Pointer<Void>), void Function(Pointer<Void>)>('free');

Pointer<T> allocBytes<T extends NativeType>(int bytes) => _calloc(bytes, 1).cast<T>();
void freeBytes(Pointer<NativeType> p) => _free(p.cast<Void>());

/// %LOCALAPPDATA%\Publishers\<PublisherId>\clogem, the folder every package of
/// the publisher shares (manifest windows.publisherCacheFolders). The physical
/// path is community knowledge, not documented by Microsoft: the VM test
/// records whether it exists after the first launch.
String? publisherCacheFolder(String? pfn) {
  final local = Platform.environment['LOCALAPPDATA'];
  if (pfn == null || local == null) return null;
  final id = pfn.substring(pfn.lastIndexOf('_') + 1);
  return '$local\\Publishers\\$id\\clogem';
}

void main(List<String> argv) async {
  final root = packageRoot();
  Directory.current = root;
  final pfn = packageFamilyName();
  final shared = publisherCacheFolder(pfn);
  final args = argv.isEmpty ? ['daemon'] : argv;
  final env = <String, String>{if (shared != null) 'CLOGEM_SHARED_DIR': shared};
  final local = Platform.environment['LOCALAPPDATA'] ?? root;
  final logDir = Directory('$local\\clogem\\run');
  String outcome;
  int exit = 0;
  try {
    logDir.createSync(recursive: true);
    final bb = '$root\\bin\\bb.exe';
    final config = '$root\\hub\\bb.edn';
    if (args.first == '--uri') {
      outcome = 'protocol activation received: ${args.sublist(1).join(' ')} (not forwarded: prototype)';
    } else {
      final p = await Process.start(bb, ['--config', config, ...args],
          environment: env, workingDirectory: root, mode: ProcessStartMode.inheritStdio);
      exit = await p.exitCode;
      outcome = 'bb exited $exit';
    }
  } catch (e) {
    outcome = 'launch failed: $e';
    exit = 1;
  }
  final line = jsonEncode({
    'ts': DateTime.now().toIso8601String(),
    'root': root,
    'package_family_name': pfn,
    'shared_dir': shared,
    'shared_dir_exists': shared == null ? null : Directory(shared).existsSync(),
    'args': args,
    'outcome': outcome,
    'log_dir': logDir.path,
  });
  try {
    File('${logDir.path}\\launcher.log').writeAsStringSync('$line\n', mode: FileMode.append);
  } catch (_) {}
  stdout.writeln(line);
  exitCode = exit;
}

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Bridges Dart/Flutter errors and explicit events into the native, privacy-
/// filtered OperationLog so they appear in exported diagnostics.
abstract final class DextopLogger {
  static const MethodChannel _channel = MethodChannel('app.freedextop/display');
  static bool _installed = false;

  /// Call from BOTH entry points: main() and overlayMain().
  static void install() {
    if (_installed) return;
    _installed = true;

    FlutterError.onError = (FlutterErrorDetails details) {
      FlutterError.presentError(details);
      error(
        'FlutterError',
        details.exceptionAsString(),
        fields: {
          'library': details.library ?? 'unknown',
          'context': details.context?.toString() ?? '',
        },
        stack: details.stack,
      );
    };

    PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
      DextopLogger.error(
        'PlatformDispatcher',
        error.toString(),
        fields: const {'engine': 'dart.vm'},
        stack: stack,
      );
      return true; // Persisted locally; do not tear down the app.
    };
  }

  static void info(String tag, String message, [Map<String, Object?> fields = const {}]) =>
      _send('i', tag, message, fields, null);

  static void warning(String tag, String message, [Map<String, Object?> fields = const {}]) =>
      _send('w', tag, message, fields, null);

  static void error(
    String tag,
    String message, {
    Map<String, Object?> fields = const {},
    StackTrace? stack,
  }) => _send('e', tag, message, fields, stack?.toString());

  static void _send(
    String level,
    String tag,
    String message,
    Map<String, Object?> fields,
    String? stack,
  ) {
    unawaited(_channel.invokeMethod<void>('logEvent', <String, Object?>{
      'level': level,
      'tag': tag,
      'message': message,
      'fields': fields,
      'stack': ?stack,
    }).catchError((Object _) {
      // Logging must never break the app; logcat is a second copy.
    }));
  }
}

import 'package:flutter/material.dart';
import 'package:call_notification_plugin/call_notification.dart';
import 'package:call_notification_plugin/call_data.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CallNotification.instance.init();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CallNotification Plugin 示例',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      home: const DemoPage(),
    );
  }
}

class DemoPage extends StatefulWidget {
  const DemoPage({super.key});

  @override
  State<DemoPage> createState() => _DemoPageState();
}

class _DemoPageState extends State<DemoPage> {
  String _lastAction = '暂无操作';
  int _callCount = 0;

  Future<void> _requestPermissions() async {
    final granted = await CallNotification.instance.requestPermissions();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(granted ? '权限已授权' : '部分未授权')),
    );
  }

  Future<void> _showVideoCall() async {
    _callCount++;
    await CallNotification.instance.showCallNotification(
      CallData(callerName: '视频来电', callType: CallType.video, callId: 'v$_callCount'),
    );
  }

  Future<void> _showAudioCall() async {
    _callCount++;
    await CallNotification.instance.showCallNotification(
      CallData(callerName: '语音来电', callType: CallType.audio, callId: 'a$_callCount'),
    );
  }

  @override
  void initState() {
    super.initState();
    CallNotification.instance.onAction = (action, callId) {
      if (!mounted) return;
      setState(() => _lastAction = '${action.name} ($callId)');
    };
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('CallNotification Plugin')),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(children: [
            Container(
              width: double.infinity, padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(color: Colors.grey.shade200, borderRadius: BorderRadius.circular(12)),
              child: Text('最近操作: $_lastAction', textAlign: TextAlign.center),
            ),
            const SizedBox(height: 24),
            SizedBox(width: double.infinity, height: 56,
              child: FilledButton.icon(onPressed: _requestPermissions,
                icon: const Icon(Icons.security), label: const Text('请求权限'))),
            const SizedBox(height: 16),
            SizedBox(width: double.infinity, height: 56,
              child: FilledButton.icon(onPressed: _showVideoCall,
                icon: const Icon(Icons.videocam), label: const Text('模拟视频来电'))),
            const SizedBox(height: 12),
            SizedBox(width: double.infinity, height: 56,
              child: FilledButton.icon(onPressed: _showAudioCall,
                icon: const Icon(Icons.phone), label: const Text('模拟语音来电'))),
          ]),
        ),
      ),
    );
  }
}

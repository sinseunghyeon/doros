
import 'package:flutter/material.dart';

import '../cat.dart';
import '../native.dart';
import '../ship_controller.dart';
import '../app_colors.dart';
import '../ui.dart';

/// 메인 탭: 고양이 선장 + 선박 시간 조절
class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.controller});
  final ShipController controller;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _bumpTick = 0;
  String _catLine = '안녕하냥, 선장님 🐾';

  static const _catLines = [
    '선박 시간 맞춰뒀다냥!',
    '좋은 항해 되라냥 ⛵',
    '시간대만 바꿨다냥',
    'UTC는 그대로다냥 👌',
    '또 눌러도 괜찮다냥',
  ];

  ShipController get c => widget.controller;

  /// [deltaMinutes]: ±60 / ±30
  void _adjust(int deltaMinutes) {
    setState(() {
      _bumpTick++;
      _catLine = (_catLines.toList()..shuffle()).first;
    });
    c.adjust(deltaMinutes);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: c,
      builder: (context, _) {
        final s = c.state;
        if (s == null) {
          return const Center(child: CircularProgressIndicator());
        }
        return RefreshIndicator(
          onRefresh: c.refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              _catHeader(),
              const SizedBox(height: 10),
              _clockCard(s),
              const SizedBox(height: 12),
              _ddayCard(s),
              const SizedBox(height: 12),
              _syncCard(s),
            ],
          ),
        );
      },
    );
  }

  Widget _catHeader() {
    return Column(children: [
      CatMascot(size: 150, bumpTick: _bumpTick),
      SpeechBubble(c.busy ? '맞추는 중이다냥…' : _catLine),
    ]);
  }

  Widget _clockCard(ShipState s) {
    final theme = Theme.of(context).textTheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(children: [
              Text('SHIP TIME',
                  style: theme.labelMedium
                      ?.copyWith(color: AppColors.sub, letterSpacing: 1.4, fontWeight: FontWeight.w700)),
              const Spacer(),
              _offsetPicker(s),
            ]),
            const SizedBox(height: 2),
            // 시계만 1초마다 갱신 (페이지 전체 리빌드 안 함)
            ValueListenableBuilder<int>(
              valueListenable: c.clock,
              builder: (context, _, __) {
                final utc = DateTime.now().toUtc();
                final ship = utc.add(Duration(minutes: s.offsetMinutes));
                final device = utc.add(Duration(minutes: s.systemOffsetMinutes));
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    FittedBox(
                      alignment: Alignment.centerLeft,
                      fit: BoxFit.scaleDown,
                      child: Text(fmtTime(ship),
                          style: theme.displayLarge?.copyWith(
                              color: AppColors.cream,
                              fontWeight: FontWeight.w800,
                              fontFeatures: const [FontFeature.tabularFigures()])),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'UTC ${fmtTime(utc, sec: false)}   ·   기기 시계 ${fmtTime(device, sec: false)} (${s.systemZone})',
                      style: theme.bodySmall?.copyWith(color: AppColors.sub),
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 16),
            _adjustRow(s, 60),
            const SizedBox(height: 10),
            _adjustRow(s, 30),
            const SizedBox(height: 12),
            _statusLine(s),
          ],
        ),
      ),
    );
  }

  /// [step] 분 단위 ±버튼 한 줄 (60 → ±1h, 30 → ±30m)
  Widget _adjustRow(ShipState s, int step) {
    final unit = step == 60 ? '1h' : '${step}m';
    final atMin = s.offsetMinutes <= ShipState.minOffset;
    final atMax = s.offsetMinutes >= ShipState.maxOffset;
    return Row(children: [
      Expanded(
        child: PawButton(
          label: '−$unit',
          hint: '늦추기',
          onTap: c.busy || atMin ? null : () => _adjust(-step),
        ),
      ),
      const SizedBox(width: 12),
      Expanded(
        child: PawButton(
          label: '+$unit',
          hint: '당기기',
          onTap: c.busy || atMax ? null : () => _adjust(step),
        ),
      ),
    ]);
  }

  Widget _offsetPicker(ShipState s) {
    return DropdownButton<int>(
      value: s.offsetMinutes,
      underline: const SizedBox.shrink(),
      isDense: true,
      style: const TextStyle(fontFamily: 'Pretendard', fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
      items: [
        for (var o = ShipState.minOffset; o <= ShipState.maxOffset; o += 30)
          DropdownMenuItem(value: o, child: Text(offLabel(o))),
      ],
      onChanged: c.busy ? null : (o) => o == null ? null : c.setOffset(o),
    );
  }

  Widget _statusLine(ShipState s) {
    late final String text;
    late final Color color;
    if (c.busy) {
      text = '적용 중…';
      color = AppColors.warn;
    } else if (!s.sync) {
      text = '표시 전용 모드 — 기기 시계는 바뀌지 않습니다';
      color = Colors.white60;
    } else if (!s.systemSupported) {
      text = '${s.label}에 해당하는 시스템 시간대가 없어 표시만 합니다 — 기기 시계는 바뀌지 않습니다';
      color = AppColors.warn;
    } else if (s.statusState == ShipState.stateFail) {
      text = s.statusText.isEmpty ? '적용 실패' : s.statusText;
      color = AppColors.fail;
    } else if (s.systemMatches) {
      text = '기기 시계가 선박 시간과 일치합니다';
      color = AppColors.ok;
    } else {
      text = '기기 시계(${offLabel(s.systemOffsetMinutes)})가 선박 시간과 다릅니다';
      color = AppColors.warn;
    }
    return Row(children: [
      if (c.busy)
        const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
      else
        Icon(Icons.circle, size: 10, color: color),
      const SizedBox(width: 8),
      Expanded(child: Text(text, style: TextStyle(color: color))),
    ]);
  }

  Widget _ddayCard(ShipState s) {
    final theme = Theme.of(context).textTheme;
    final label = s.ddayLabel;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 8, 16),
        child: Row(children: [
          Expanded(
            child: label == null
                ? Text('디데이 없음 — 입항일·하선일 등을 등록하면 위젯에도 표시됩니다',
                    style: theme.bodyMedium?.copyWith(color: AppColors.sub))
                : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(label,
                        style: theme.headlineMedium
                            ?.copyWith(color: AppColors.cream, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 2),
                    Text(
                      [if (s.ddayTitle.isNotEmpty) s.ddayTitle, s.ddayDate ?? ''].join(' · '),
                      style: theme.bodySmall?.copyWith(color: AppColors.sub),
                    ),
                  ]),
          ),
          IconButton(
            tooltip: label == null ? '디데이 추가' : '디데이 수정',
            onPressed: c.busy ? null : () => _editDday(s),
            icon: Icon(label == null ? Icons.add : Icons.edit_outlined),
          ),
          if (label != null)
            IconButton(
              tooltip: '디데이 삭제',
              onPressed: c.busy ? null : () => c.setDday('', null),
              icon: const Icon(Icons.delete_outline),
            ),
        ]),
      ),
    );
  }

  Future<void> _editDday(ShipState s) async {
    // 기본 날짜: 선박 시간 기준 오늘
    final shipNow = DateTime.now().toUtc().add(Duration(minutes: s.offsetMinutes));
    final today = DateTime(shipNow.year, shipNow.month, shipNow.day);
    final r = await showDialog<({String title, String date})>(
      context: context,
      builder: (_) => _DdayDialog(
        title: s.ddayTitle,
        date: DateTime.tryParse(s.ddayDate ?? '') ?? today,
      ),
    );
    if (r == null || !mounted) return;
    await c.setDday(r.title, r.date);
  }

  Widget _syncCard(ShipState s) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          SwitchListTile(
            title: const Text('기기 시간대도 같이 변경'),
            subtitle: const Text(
                '끄면 위젯/앱에만 선박 시간을 표시합니다. 켜면 시스템 시간대를 바꿉니다. UTC 기준 시각은 건드리지 않습니다.'),
            value: s.sync,
            onChanged: c.busy ? null : (v) => c.setSync(v),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Wrap(spacing: 8, runSpacing: 8, children: [
              OutlinedButton.icon(
                onPressed: c.busy || !s.sync ? null : c.reapply,
                icon: const Icon(Icons.sync, size: 18),
                label: const Text('지금 다시 적용'),
              ),
              OutlinedButton.icon(
                onPressed: c.busy ? null : c.adoptSystemOffset,
                icon: const Icon(Icons.download, size: 18),
                label: const Text('기기 시간대로 맞추기'),
              ),
            ]),
          ),
        ]),
      ),
    );
  }
}

/// 디데이 입력 다이얼로그 — TextEditingController 수명을 State 가 소유한다.
class _DdayDialog extends StatefulWidget {
  const _DdayDialog({required this.title, required this.date});
  final String title;
  final DateTime date;

  @override
  State<_DdayDialog> createState() => _DdayDialogState();
}

class _DdayDialogState extends State<_DdayDialog> {
  late final _titleCtrl = TextEditingController(text: widget.title);
  late DateTime _date = widget.date;

  @override
  void dispose() {
    _titleCtrl.dispose();
    super.dispose();
  }

  String get _dateText =>
      '${_date.year.toString().padLeft(4, '0')}-${_date.month.toString().padLeft(2, '0')}-${_date.day.toString().padLeft(2, '0')}';

  Future<void> _pickDate() async {
    final d = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2000),
      lastDate: DateTime(2100, 12, 31),
    );
    if (d != null && mounted) setState(() => _date = d);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('디데이'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextField(
          controller: _titleCtrl,
          maxLength: 12,
          decoration: const InputDecoration(
            labelText: '제목 (선택)',
            hintText: '예: 입항, 하선',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: _pickDate,
          icon: const Icon(Icons.event, size: 18),
          label: Text(_dateText),
        ),
        const SizedBox(height: 8),
        const Text('선박 시간 기준으로 날짜를 셉니다.', style: TextStyle(color: AppColors.sub, fontSize: 12)),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('취소')),
        FilledButton(
          onPressed: () => Navigator.pop(context, (title: _titleCtrl.text.trim(), date: _dateText)),
          child: const Text('저장'),
        ),
      ],
    );
  }
}

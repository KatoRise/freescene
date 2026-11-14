import 'package:flutter/material.dart';

import '../domain/source.dart';
import 'source_checks.dart';

/// Catalogue counts are not evidence of a successful connection.
class AvailabilityIndicator extends StatelessWidget {
  const AvailabilityIndicator({
    super.key,
    required this.source,
    required this.english,
  });

  final SourceRow source;
  final bool english;

  @override
  Widget build(BuildContext context) => ValueListenableBuilder(
    valueListenable: sourceCheckResults,
    builder: (context, _, child) => _content(context),
  );
  Widget _content(BuildContext context) {
    if (!source.isTorrent) return const Text('—');
    final counts = source.catalogueCounts;
    final summary = english
        ? 'Catalogue: seeds ${counts.seedsLabel} · peers ${counts.peersLabel}'
        : 'Каталог: сиды ${counts.seedsLabel} · пиры ${counts.peersLabel}';
    final state = recentSourceCheck('${source.provider.name}:${source.id}')
        ?.state;
    final status = switch (state) {
      SourceCheckState.checking => english ? 'Checking…' : 'Проверяется…',
      SourceCheckState.data =>
        english ? 'Video data received' : 'Данные видео поступают',
      SourceCheckState.timeout =>
        english
            ? 'No data within the check time'
            : 'За время проверки данные не получены',
      SourceCheckState.unavailable =>
        english ? 'Could not check' : 'Не удалось проверить',
      null => english ? 'Connection not checked' : 'Подключение не проверено',
    };
    final label = '$summary\n$status';
    return Tooltip(
      message: label,
      excludeFromSemantics: true,
      child: Semantics(
        label: label,
        child: ExcludeSemantics(child: Text('$summary · $status')),
      ),
    );
  }
}

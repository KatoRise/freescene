import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:freescene/app.dart';
import 'package:freescene/android/android_app.dart';
import 'package:freescene/android/android_runtime.dart';
import 'package:freescene/settings/preferences.dart';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:freescene/catalog/catalog_models.dart';
import 'package:freescene/catalog/watched.dart';
import 'package:freescene/history/history_playback.dart';
import 'package:freescene/history/watch_history.dart';
import 'package:freescene/torrent/native_torrent.dart';

const provider = SourceProvider('fixture');
const title = CatalogTitle(id: '42', kind: CatalogKind.series, name: 'Series');
final source = CatalogSource(
  row: const SourceRow(
    id: 'wanted',
    provider: provider,
    title: 'Series season',
    watch: SourceKind.embed,
    download: DownloadKind.unavailable,
  ),
  uri: Uri.parse('https://fixture.invalid/player'),
  webEpisodes: [
    CatalogWebEpisode(
      url: Uri.parse('https://fixture.invalid/episode/1'),
      title: 'Episode 1',
    ),
    CatalogWebEpisode(
      url: Uri.parse('https://fixture.invalid/episode/2'),
      title: 'Episode 2',
    ),
  ],
);

class Repository extends CatalogRepository {
  bool enabled = true, missing = false;
  int searches = 0;
  final pages = <int>[];
  @override
  List<SourceProvider> providers(CatalogTitle title) =>
      enabled ? [provider] : [];
  @override
  Future<CatalogTitle> details(CatalogTitle title) async => title;
  @override
  Future<CatalogPage<CatalogTitle>> list(
    CatalogKind kind, {
    String search = '',
    CatalogFilter filter = const CatalogFilter(),
    int start = 0,
  }) async {
    searches++;
    return CatalogPage(
      items: kind == CatalogKind.series ? [title] : [],
      total: 1,
      start: 0,
    );
  }

  @override
  Future<CatalogPage<CatalogSource>> sources(
    CatalogTitle title, {
    int start = 0,
  }) async {
    pages.add(start);
    return CatalogPage(
      items: start == 0 || missing ? [] : [source],
      total: missing ? 0 : 1,
      start: start,
      nextStart: start == 0 && !missing ? 20 : null,
    );
  }
}

WatchEntry entry({bool legacy = false, String? id}) => WatchEntry.parse({
  'id': id ?? 'a' * 64,
  'watchedAt': 1000,
  'title': 'Series',
  'provider': 'fixture',
  'sourceType': 'provider',
  'subtitle': 'Episode 2',
  if (!legacy) ...sourceWatchHistory(title, source, false),
  if (!legacy) 'webEpisodeIndex': 1,
})!;

class TorrentService extends NativeTorrentService {
  final calls = <(String, Map<String, dynamic>?)>[];
  bool prepared = false;
  final files = <Map<String, dynamic>>[
    for (var i = 0; i < 2; i++)
      {
        'index': i,
        'name': 'S01E0${i + 1}.mp4',
        'size': 100,
        'done': 100,
        'video': true,
      },
  ];
  int replayFile = 1;
  @override
  Future<dynamic> call(String method, [Map<String, dynamic>? args]) async {
    calls.add((method, args));
    if (method == 'historyReplay') return {'id': 'a' * 40, 'file': replayFile};
    if (method == 'preparePlayer') prepared = true;
    if (method == 'status') {
      return {
        'playerTaskId': prepared ? 'a' * 40 : null,
        'tasks': [
          {
            'id': 'a' * 40,
            'title': 'Series',
            'origin': {'local': 'magnet'},
            'phase': 'paused',
            'done': 200,
            'total': 200,
            'rate': 0,
            'seeds': 0,
            'peers': 0,
            'error': '',
            'event': '',
            'metadata': true,
            'files': files,
          },
        ],
      };
    }
    return {};
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final platform in ['desktop', 'phone', 'tv']) {
    testWidgets('history opens exact web episode directly on $platform', (
      tester,
    ) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = platform == 'phone'
          ? const Size(390, 844)
          : const Size(1280, 800);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      final native = TorrentService();
      final calls = <MethodCall>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      for (final channel in [
        NativeTorrentService.channel,
        AndroidRuntime.channel,
      ]) {
        messenger.setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'watchHistorySnapshot') {
            return {
              'entries': [entry().data],
            };
          }
          return <String, Object>{};
        });
        addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      }
      await tester.pumpWidget(
        platform == 'desktop'
            ? FreeSceneApp(
                initialPreferences: Preferences(),
                savePreferences: (_) async {},
                catalogRepository: Repository(),
                nativeService: native,
              )
            : AndroidApp(
                runtime: AndroidRuntime(
                  profile: Directory('/tmp/history-replay-profile'),
                  cache: Directory('/tmp/history-replay-cache'),
                  tv: platform == 'tv',
                  lowRam: false,
                  version: '1.8.7',
                ),
                initialPreferences: Preferences(),
                savePreferences: (_) async {},
                repository: Repository(),
              ),
      );
      await tester.pumpAndSettle();
      if (platform == 'phone') {
        await tester.tap(find.byKey(const ValueKey('remote-lists')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('lists-history')));
      } else {
        await tester.tap(
          find.byKey(
            ValueKey(
              platform == 'desktop' ? 'sidebar-history' : 'remote-history',
            ),
          ),
        );
      }
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(ValueKey('history-row-${entry().id}')));
      await tester.pumpAndSettle();
      if (platform == 'desktop') {
        expect(
          native.calls.where((c) => c.$1 == 'openProvider').single.$2!['url'],
          source.webEpisodes[1].url.toString(),
        );
      } else {
        expect(
          calls.where((c) => c.method == 'playWeb').single.arguments['url'],
          source.webEpisodes[1].url.toString(),
        );
      }
      expect(find.text('История просмотра'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('catalog-details-toolbar')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }

  test(
    'new entries resolve exact source on later pages without name search',
    () async {
      final repository = Repository();
      final result = await resolveHistorySource(entry(), repository);
      expect(result.title.id, title.id);
      expect(result.source, same(source));
      expect(repository.searches, 0);
      expect(repository.pages, [0, 20]);
      expect(
        historyPlaybackUrl(entry(), source.uri!, source.webEpisodes),
        source.webEpisodes[1].url,
      );
      expect(entry().watchedIdentity, watchedIdentity(title, source));
    },
  );
  test(
    'legacy web entry resolves by original hash, never just the name',
    () async {
      final key = watchedIdentity(title, source)['watchedSource'];
      final id = sha256
          .convert(utf8.encode('$key:episode:1:Episode 2'))
          .toString();
      final result = await resolveHistorySource(
        entry(legacy: true, id: id),
        Repository(),
      );
      expect(result.source.row.id, 'wanted');
      expect(
        historyPlaybackUrl(
          entry(legacy: true, id: id),
          source.uri!,
          result.source.webEpisodes,
        ),
        source.webEpisodes[1].url,
      );
      await expectLater(
        resolveHistorySource(entry(legacy: true), Repository()),
        throwsFormatException,
      );
    },
  );
  test(
    'missing source, disabled provider and missing episode fail explicitly',
    () async {
      await expectLater(
        resolveHistorySource(entry(), Repository()..missing = true),
        throwsFormatException,
      );
      await expectLater(
        resolveHistorySource(entry(), Repository()..enabled = false),
        throwsFormatException,
      );
      expect(
        () => historyPlaybackUrl(
          entry(),
          source.uri!,
          source.webEpisodes.take(1).toList(),
        ),
        throwsFormatException,
      );
      expect(
        historyPlaybackUrl(
          entry(),
          source.uri!,
          source.webEpisodes.reversed.toList(),
        ),
        source.webEpisodes[1].url,
      );
    },
  );
  test(
    'desktop replay bypasses picker and selects exact history file',
    () async {
      final service = TorrentService();
      var picked = false;
      final controller = TorrentController(
        service,
        () => {},
        chooseVideo: (_) async {
          picked = true;
          return null;
        },
      );
      addTearDown(controller.dispose);
      await controller.replayHistory('b' * 64, {
        'title': 'Series',
        'sourceType': 'torrent',
      });
      await controller.refresh();
      expect(picked, false);
      expect(
        service.calls.where((c) => c.$1 == 'stream').single.$2!['file'],
        1,
      );
    },
  );
  test('missing history file never falls back to first video', () async {
    final service = TorrentService()..replayFile = 9;
    final controller = TorrentController(service, () => {});
    addTearDown(controller.dispose);
    await controller.replayHistory('b' * 64, {
      'title': 'Series',
      'sourceType': 'torrent',
    });
    await controller.refresh();
    expect(service.calls.where((c) => c.$1 == 'stream'), isEmpty);
    expect(service.calls.where((c) => c.$1 == 'playerMessage'), isNotEmpty);
  });
}

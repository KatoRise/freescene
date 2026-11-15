#import "../../macos/Native/FSWatchHistory.h"
int main(void) { @autoreleasepool {
    NSCAssert(NSProcessInfo.processInfo.environment[@"FREESCENE_DATA"], @"Use an isolated profile");
    NSDictionary *base=@{@"title":@"Series",@"sourceType":@"torrent",@"provider":@"Zona"};
    FSWatchLaunch *clock=[FSWatchLaunch new];[clock select:@"torrent:abc:1" metadata:base];
    [clock sample:YES at:0];[clock sample:YES at:1];[clock sample:NO at:2.999];
    NSCAssert([FSWatchSnapshot()[@"entries"] count]==0,@"Less than 3 seconds is absent");
    [clock sample:NO at:100];[clock sample:YES at:101];[clock sample:YES at:101.01];
    NSCAssert([FSWatchSnapshot()[@"entries"] count]==1,@"Three seconds accumulated excluding pause");
    NSNumber *time=FSWatchSnapshot()[@"entries"][0][@"watchedAt"];[clock sample:YES at:102];
    NSCAssert([time isEqual:FSWatchSnapshot()[@"entries"][0][@"watchedAt"]],@"Once per launch");
    FSWatchRecord(@"torrent:abc:1",@{@"title":@"Series",@"sourceType":@"torrent",@"subtitle":@"/private/Series.S01E01.mkv",@"url":@"https://secret/?token=yes"},2000);
    FSWatchRecord(@"torrent:abc:2",base,3000);
    NSArray *entries=FSWatchSnapshot()[@"entries"];
    NSCAssert(entries.count==2 && [entries[0][@"id"] isEqual:FSWatchHash(@"torrent:abc:2")],@"Distinct files sorted");
    NSCAssert([entries[1][@"provider"] isEqual:@"Zona"] && [entries[1][@"subtitle"] isEqual:@"Series.S01E01.mkv"],@"Merge known fields and basename only");
    NSCAssert(!entries[1][@"url"],@"No URL stored");
    NSCAssert([FSWatchRemove(@[entries[0][@"id"]]) integerValue]==1,@"Delete only requested entry");
    NSCAssert(FSWatchRemove(@[@"invalid"])==nil,@"Reject invalid ids");
    for(int i=0;i<502;i++)FSWatchRecord([NSString stringWithFormat:@"file:%d",i],base,4000+i);
    entries=FSWatchSnapshot()[@"entries"];
    NSCAssert(entries.count==500 && [entries[0][@"watchedAt"] integerValue]==4501,@"Bounded and newest first");
    [@"{broken" writeToFile:FSWatchHistoryPath() atomically:YES encoding:NSUTF8StringEncoding error:nil];
    NSCAssert([FSWatchSnapshot()[@"entries"] count]==0,@"Corrupt store recovers");
    NSCAssert(FSWatchRecord(@"fresh",base,6000),@"Can write after corruption");
    puts("watch history: three-second threshold, pause, deduplication, removal, metadata, limit and corruption passed");
} return 0; }

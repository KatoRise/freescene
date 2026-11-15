#pragma once
#import <Foundation/Foundation.h>
#import <CommonCrypto/CommonDigest.h>

static inline NSString *FSWatchHistoryPath(void) {
    NSString *root=NSProcessInfo.processInfo.environment[@"FREESCENE_DATA"] ?: [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/FreeScene"];
    return [root stringByAppendingPathComponent:@"watch-history.json"];
}
static inline NSString *FSWatchHash(NSString *value) {
    NSData *data=[value dataUsingEncoding:NSUTF8StringEncoding];unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes,(CC_LONG)data.length,digest);NSMutableString *result=[NSMutableString new];
    for(int i=0;i<CC_SHA256_DIGEST_LENGTH;i++)[result appendFormat:@"%02x",digest[i]];return result;
}
static inline BOOL FSWatchNumber(id value,double min,double max) {
    return [value isKindOfClass:NSNumber.class] && CFGetTypeID((__bridge CFTypeRef)value)!=CFBooleanGetTypeID() && isfinite([value doubleValue]) && [value doubleValue]>=min && [value doubleValue]<=max && floor([value doubleValue])==[value doubleValue];
}
static inline BOOL FSWatchID(id value) {
    return [value isKindOfClass:NSString.class] && [value length]==64 && [value rangeOfCharacterFromSet:[[NSCharacterSet characterSetWithCharactersInString:@"0123456789abcdef"] invertedSet]].location==NSNotFound;
}
static inline NSMutableDictionary *FSWatchClean(id raw) {
    NSMutableDictionary *out=[NSMutableDictionary new];if(![raw isKindOfClass:NSDictionary.class])return out;
    for(NSString *field in @[@"title",@"subtitle",@"provider",@"quality"]) {
        id v=raw[field];if(![v isKindOfClass:NSString.class])continue;
        NSString *value=[v stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if([value rangeOfString:@"://"].location!=NSNotFound || [value rangeOfString:@"magnet:" options:NSCaseInsensitiveSearch].location!=NSNotFound ||
           [value rangeOfString:@"(?:token|cookie|authorization|password)\\s*=" options:NSRegularExpressionSearch|NSCaseInsensitiveSearch].location!=NSNotFound)continue;
        if([field isEqual:@"subtitle"])value=[[value stringByReplacingOccurrencesOfString:@"\\" withString:@"/"] lastPathComponent];
        else if([value hasPrefix:@"/"] || [value rangeOfString:@"^[A-Za-z]:[\\\\/]" options:NSRegularExpressionSearch].location!=NSNotFound)continue;
        value=[[value componentsSeparatedByCharactersInSet:NSCharacterSet.controlCharacterSet] componentsJoinedByString:@""];
        NSUInteger limit=[field isEqual:@"quality"] ? 64 : 1024;if(value.length>limit)value=[value substringToIndex:limit];
        if(value.length && ![value isEqual:@"—"])out[field]=value;
    }
    for(NSString *field in @[@"season",@"episode",@"sizeBytes"]) {
        double max=[field isEqual:@"season"] ? 999 : [field isEqual:@"episode"] ? 9999 : 9007199254740991.;
        if(FSWatchNumber(raw[field],[field isEqual:@"sizeBytes"] ? 1 : 0,max))out[field]=raw[field];
    }
    if([@[@"torrent",@"provider",@"download"] containsObject:raw[@"sourceType"] ?: @""])out[@"sourceType"]=raw[@"sourceType"];
    return out;
}
static inline NSArray *FSWatchSorted(NSArray *values) {
    NSArray *sorted=[values sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *a,NSDictionary *b) {
        NSComparisonResult time=[b[@"watchedAt"] compare:a[@"watchedAt"]];return time==NSOrderedSame ? [a[@"id"] compare:b[@"id"]] : time;
    }];
    NSMutableSet *ids=[NSMutableSet new];NSMutableArray *result=[NSMutableArray new];
    for(NSDictionary *entry in sorted)if(![ids containsObject:entry[@"id"]] && result.count<500) { [ids addObject:entry[@"id"]];[result addObject:entry]; }
    return result;
}
static inline NSArray *FSWatchRead(NSError **error) {
    NSError *failure=nil;NSData *data=[NSData dataWithContentsOfFile:FSWatchHistoryPath() options:0 error:&failure];
    if(!data) { if(failure.code!=NSFileReadNoSuchFileError && error)*error=failure;return @[]; }
    id root=[NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    if(![root isKindOfClass:NSDictionary.class] || !FSWatchNumber(root[@"version"],1,1) || ![root[@"entries"] isKindOfClass:NSArray.class])return @[];
    NSMutableArray *entries=[NSMutableArray new];NSUInteger checked=0;
    for(id raw in root[@"entries"]) {
        if(++checked>5000)break;
        NSMutableDictionary *entry=FSWatchClean(raw);
        if(!entry[@"title"] || !entry[@"sourceType"] || !FSWatchID(raw[@"id"]) || !FSWatchNumber(raw[@"watchedAt"],1,8640000000000000.))continue;
        entry[@"id"]=raw[@"id"];entry[@"watchedAt"]=raw[@"watchedAt"];[entries addObject:entry];
    }
    return FSWatchSorted(entries);
}
static inline BOOL FSWatchWrite(NSArray *entries) {
    NSString *path=FSWatchHistoryPath();
    if(![NSFileManager.defaultManager createDirectoryAtPath:path.stringByDeletingLastPathComponent withIntermediateDirectories:YES attributes:nil error:nil])return NO;
    NSData *data=[NSJSONSerialization dataWithJSONObject:@{@"version":@1,@"entries":FSWatchSorted(entries)} options:0 error:nil];
    return [data writeToFile:path options:NSDataWritingAtomic error:nil];
}
static inline BOOL FSWatchRecord(NSString *key,NSDictionary *metadata,long long now) {
    @synchronized(NSFileManager.defaultManager) {
        NSMutableDictionary *entry=FSWatchClean(metadata);if(!entry[@"title"] || !entry[@"sourceType"] || now<=0)return NO;
        NSError *error=nil;NSMutableArray *entries=[FSWatchRead(&error) mutableCopy];if(error)return NO;
        NSString *identity=FSWatchHash(key ?: @"");NSMutableDictionary *merged=[NSMutableDictionary new];
        for(NSDictionary *old in entries)if([old[@"id"] isEqual:identity])[merged addEntriesFromDictionary:old];
        [merged addEntriesFromDictionary:entry];merged[@"id"]=identity;merged[@"watchedAt"]=@(now);
        [entries filterUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(NSDictionary *old,NSDictionary *bindings){return ![old[@"id"] isEqual:identity];}]];
        [entries addObject:merged];return FSWatchWrite(entries);
    }
}
static inline NSDictionary *FSWatchSnapshot(void) {
    @synchronized(NSFileManager.defaultManager) { NSError *error=nil;NSArray *entries=FSWatchRead(&error);return error ? nil : @{@"entries":entries}; }
}
static inline NSNumber *FSWatchRemove(id ids) {
    if(![ids isKindOfClass:NSArray.class] || [ids count]>500)return nil;for(id identity in ids)if(!FSWatchID(identity))return nil;
    @synchronized(NSFileManager.defaultManager) {
        NSError *error=nil;NSArray *old=FSWatchRead(&error);if(error)return nil;
        NSArray *kept=[old filteredArrayUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(NSDictionary *entry,NSDictionary *bindings){return ![ids containsObject:entry[@"id"]];}]];
        return FSWatchWrite(kept) ? @(old.count-kept.count) : nil;
    }
}

@interface FSWatchLaunch : NSObject
@property(copy) void (^didRecord)(void);
@property NSDictionary *metadata;
@property NSString *baseKey;
@property NSString *key;
@property double elapsed,last;
@property BOOL active,recorded;
- (void)select:(NSString *)key metadata:(NSDictionary *)metadata;
- (void)sample:(BOOL)playing at:(double)now;
@end
@implementation FSWatchLaunch
- (void)select:(NSString *)key metadata:(NSDictionary *)metadata {
    self.key=key;self.metadata=metadata;self.elapsed=0;self.last=0;self.active=NO;self.recorded=NO;
}
- (void)sample:(BOOL)playing at:(double)now {
    double delta=now-self.last;
    if(!self.recorded && self.active && delta>=0 && delta<=6) {
        self.elapsed+=delta;
        if(self.elapsed>=3) { self.recorded=FSWatchRecord(self.key,self.metadata,(long long)(NSDate.date.timeIntervalSince1970*1000));if(self.recorded && self.didRecord)self.didRecord(); }
    }
    self.last=now;self.active=playing;
}
@end

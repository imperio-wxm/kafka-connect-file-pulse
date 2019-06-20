# Cleaning completed files

The connector can be configured with a specific [FileCleanupPolicy](connect-file-pulse-api/src/main/java/io/streamthoughts/kafka/connect/filepulse/clean/FileCleanupPolicy.java) implementation.

The cleanup policy can be configured with the below connect property :

| Configuration |   Description |   Type    |   Default |   Importance  |
| --------------| --------------|-----------| --------- | ------------- |
|`fs.cleanup.policy.class` | The class used by tasks to read input files | class | *-* | high |


## Available Cleanup Policies

### DeleteCleanPolicy

This policy deletes all files regardless of their final status (completed or failed).

To enable this policy, the property `fs.cleanup.policy.class` must configured to : 

```
io.streamthoughts.kafka.connect.filepulse.clean.DeleteCleanPolicy
```

#### Configuration
no configuration

### LogCleanPolicy

This policy prints to logs some information after files completion.

To enable this policy, the property `fs.cleanup.policy.class` must configured to : 

```
io.streamthoughts.kafka.connect.filepulse.clean.LogCleanPolicy 
```

#### Configuration
no configuration

### MoveCleanPolicy

This policy attempts to move atomically files to configurable target directories.

To enable this policy, the property `fs.cleanup.policy.class` must configured to : 

```
io.streamthoughts.kafka.connect.filepulse.clean.MoveCleanPolicy
```

#### Configuration

| Configuration |   Description |   Type    |   Default |   Importance  |
| --------------| --------------|-----------| --------- | ------------- |
|`cleaner.output.failed.path` | Target directory for file proceed with failure | string | *.failure* | high |
|`cleaner.output.succeed.path` | Target directory for file proceed successfully | string | *.success* | high |

## Implementing your own policy

{% include_relative plan.md %}
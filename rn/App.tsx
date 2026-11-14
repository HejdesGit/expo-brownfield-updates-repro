import { StatusBar } from 'expo-status-bar';
import * as Updates from 'expo-updates';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';

const LABEL = process.env.EXPO_PUBLIC_LABEL ?? 'embedded';

export default function App() {
  const createdAt = Updates.createdAt?.toISOString() ?? '-';
  return (
    <View style={styles.container}>
      <Text style={styles.caption}>React Native surface</Text>
      <Text style={styles.label}>{LABEL}</Text>
      <Text style={styles.row}>isEmbeddedLaunch: {String(Updates.isEmbeddedLaunch)}</Text>
      <Text style={styles.row}>updateId: {Updates.updateId ?? '-'}</Text>
      <Text style={styles.row}>createdAt: {createdAt}</Text>
      {/* Used to check an ordinary reload and that the host still has an Activity (Alert needs one). */}
      <View style={styles.buttons}>
        <Pressable style={styles.button} onPress={() => Updates.reloadAsync()}>
          <Text style={styles.buttonText}>Reload</Text>
        </Pressable>
        <Pressable style={styles.button} onPress={() => Alert.alert('Alert', `Shown by ${LABEL}`)}>
          <Text style={styles.buttonText}>Show alert</Text>
        </Pressable>
      </View>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1f7a4d',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  caption: { color: '#d8f3e4', fontSize: 16, marginBottom: 8 },
  label: { color: '#ffffff', fontSize: 64, fontWeight: 'bold', marginBottom: 24 },
  row: { color: '#ffffff', fontSize: 14, fontFamily: 'monospace', marginBottom: 6 },
  buttons: { flexDirection: 'row', gap: 12, marginTop: 24 },
  button: { backgroundColor: '#ffffff', borderRadius: 8, paddingHorizontal: 16, paddingVertical: 10 },
  buttonText: { color: '#1f7a4d', fontSize: 16, fontWeight: '600' },
});

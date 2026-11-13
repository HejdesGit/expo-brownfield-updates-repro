import { StatusBar } from 'expo-status-bar';
import * as Updates from 'expo-updates';
import { StyleSheet, Text, View } from 'react-native';

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
});
